package org.zeromq.dafka;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.UnrecognizedOptionException;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;
import org.zeromq.SocketType;
import org.zeromq.ZActor;
import org.zeromq.ZContext;
import org.zeromq.ZFrame;
import org.zeromq.ZMQ;
import org.zeromq.ZMQ.Socket;
import org.zeromq.ZMsg;
import org.zeromq.ZPoller;
import org.zproto.DafkaProto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.zeromq.ZActor.SimpleActor;

public class DafkaConsumer extends SimpleActor
{

    private static final Logger log = LogManager.getLogger();

    private Socket      consumerSub;
    private Socket      consumerPub;
    private DafkaBeacon beacon;
    private ZActor      beaconActor;

    private final DafkaProto fetchMsg;
    private final DafkaProto getHeadsMsg;
    private final DafkaProto helloMsg;

    private final Map<String, Long> sequenceIndex;
    private final List<String>      topics;

    private boolean resetLatest;

    public DafkaConsumer()
    {
        this.fetchMsg = new DafkaProto(DafkaProto.FETCH);
        this.getHeadsMsg = new DafkaProto(DafkaProto.GET_HEADS);
        this.helloMsg = new DafkaProto(DafkaProto.CONSUMER_HELLO);
        this.sequenceIndex = new HashMap<>();
        this.topics = new ArrayList<>();
        this.resetLatest = true;
        this.beacon = new DafkaBeacon();
    }

    @Override
    public List<Socket> createSockets(ZContext ctx, Object... args)
    {
        Properties properties = (Properties) args[0];
        if (StringUtils.equals(properties.getProperty("consumer.offset.reset"), "earliest")) {
            this.resetLatest = false;
        }

        this.beaconActor = new ZActor(ctx, this.beacon, null, args);
        this.beaconActor.recv(); // Wait for signal that beacon is connected to tower

        consumerSub = ctx.createSocket(SocketType.SUB);
        consumerPub = ctx.createSocket(SocketType.PUB);
        assert consumerSub != null;
        return Arrays.asList(consumerSub, beaconActor.pipe());
    }

    @Override
    public void start(Socket pipe, List<Socket> sockets, ZPoller poller)
    {
        String consumerAddress = UUID.randomUUID().toString();

        int port = consumerPub.bindToRandomPort("tcp://*");

        beacon.start(beaconActor, consumerAddress, port);
        boolean rc = poller.register(beaconActor.pipe(), ZPoller.IN);
        assert rc == true;

        fetchMsg.setAddress(consumerAddress);
        getHeadsMsg.setAddress(consumerAddress);
        helloMsg.setAddress(consumerAddress);

        DafkaProto.subscribe(consumerSub, DafkaProto.DIRECT_MSG, consumerAddress);
        DafkaProto.subscribe(consumerSub, DafkaProto.DIRECT_HEAD, consumerAddress);
        DafkaProto.subscribe(consumerSub, DafkaProto.STORE_HELLO, consumerAddress);

        rc = poller.register(consumerSub, ZPoller.IN);
        pipe.send(new byte[] { 0 });
        assert rc == true;
        log.info("Consumer started...");
    }

    @Override
    public boolean finished(Socket pipe)
    {
        beacon.terminate(beaconActor);
        log.info("Consumer stopped!");
        return super.finished(pipe);
    }

    @Override
    public boolean stage(Socket socket, Socket pipe, ZPoller poller, int events)
    {
        if (socket.equals(beaconActor.pipe())) {
            String command = socket.recvStr();
            String address = socket.recvStr();

            if ("CONNECT".equals(command)) {
                log.info("Connecting to {}", address);

                boolean rc = consumerSub.connect(address);
                assert rc;
            }
            else if ("DISCONNECT".equals(command)) {
                log.info("Disconnecting from {}", address);
                consumerSub.disconnect(address);
            }
            else {
                log.error("Transport: Unknown command {}", command);
                assert (false);
            }
        }
        else if (socket.equals(consumerSub)) {
            DafkaProto consumerMsg = DafkaProto.recv(consumerSub);
            if (consumerMsg == null) {
                return true;    // Interrupted!
            }
            final char id = consumerMsg.id();
            final String address = consumerMsg.address();
            final String subject = consumerMsg.subject();
            final long currentSequence = consumerMsg.sequence();
            final ZFrame content = consumerMsg.content();

            log.debug(
                    "Received message {} from {} on subject {} with sequence {}",
                    id,
                    address,
                    subject,
                    currentSequence);

            String sequenceKey = subject + "/" + address;

            Long lastKnownSequence = -1L;
            boolean lastSequenceKnown = sequenceIndex.containsKey(sequenceKey);
            if (lastSequenceKnown) {
                lastKnownSequence = sequenceIndex.get(sequenceKey);
            }

            switch (id) {
            case DafkaProto.MSG:
            case DafkaProto.DIRECT_MSG:
                if (!lastSequenceKnown) {
                    if (resetLatest) {
                        log.debug(
                                "Setting offset for topic {} on partition {} to latest {}",
                                subject,
                                address,
                                currentSequence - 1);
                        // Set to latest - 1 in order to process the current message
                        lastKnownSequence = currentSequence - 1;
                        sequenceIndex.put(sequenceKey, lastKnownSequence);
                    }
                    else {
                        log.debug(
                                "Setting offset for topic {} on partition {} to earliest {}",
                                subject,
                                address,
                                lastKnownSequence);
                        sequenceIndex.put(sequenceKey, lastKnownSequence);
                    }
                }

                //  Check if we missed some messages
                if (currentSequence > lastKnownSequence + 1) {
                    sendFetch(address, subject, currentSequence, lastKnownSequence);
                }

                if (currentSequence == lastKnownSequence + 1) {
                    log.debug("Send message {} to client", currentSequence);

                    sequenceIndex.put(sequenceKey, currentSequence);

                    pipe.send(subject, ZMQ.SNDMORE);
                    pipe.send(address, ZMQ.SNDMORE);
                    pipe.send(content.getData());
                }
                break;
            case DafkaProto.HEAD:
            case DafkaProto.DIRECT_HEAD:
                if (!lastSequenceKnown) {
                    if (resetLatest) {
                        log.debug(
                                "Setting offset for topic {} on partition {} to latest {}",
                                subject,
                                address,
                                currentSequence);

                        // Set to latest in order to skip fetching older messages
                        lastKnownSequence = currentSequence;
                        sequenceIndex.put(sequenceKey, lastKnownSequence);
                        lastSequenceKnown = true;
                    }
                }

                //  Check if we missed some messages
                if (!lastSequenceKnown || currentSequence > lastKnownSequence) {
                    sendFetch(address, subject, currentSequence, lastKnownSequence);
                }
                break;
            case DafkaProto.STORE_HELLO:
                String storeAddress = consumerMsg.address();

                log.info("Consumer: Consumer is connected to store {}", storeAddress);

                sendConsumerHelloMsg(storeAddress);
                break;
            default:
                log.warn("Unkown message type {}", id);
                return true;     // Unexpected message id
            }
        }
        return true;
    }

    private void sendGetHeadsMsg(final String topic)
    {
        log.debug("Consumer: Send EARLIEST message for topic {}", topic);

        getHeadsMsg.setTopic(topic);
        getHeadsMsg.send(consumerPub);
    }

    private void sendFetch(String address, String subject, long currentSequence, Long lastKnownSequence)
    {
        long noOfMissedMessages = currentSequence - lastKnownSequence;
        log.debug(
                "FETCHING {} messages on subject {} from {} starting at sequence {}",
                noOfMissedMessages,
                subject,
                address,
                lastKnownSequence + 1);

        fetchMsg.setTopic(address);
        fetchMsg.setSubject(subject);
        fetchMsg.setSequence(lastKnownSequence + 1);
        fetchMsg.setCount(noOfMissedMessages);
        fetchMsg.send(consumerPub);
    }

    private void sendConsumerHelloMsg(String storeAddress)
    {
        List<String> topics;

        if (!this.resetLatest) {
            topics = new ArrayList<>(this.topics);
        }
        else {
            topics = new ArrayList<>();
        }

        this.helloMsg.setSubjects(topics);
        this.helloMsg.setTopic(storeAddress);
        this.helloMsg.send(this.consumerPub);
    }

    @Override
    public boolean backstage(Socket pipe, ZPoller poller, int events)
    {
        String command = pipe.recvStr();
        switch (command) {
        case "$TERM":
            return false;
        default:
            log.error("Invalid command {}", command);
        }
        return true;
    }

    public void subscribe(String topic)
    {
        log.debug("Subscribe to topic {}", topic);
        DafkaProto.subscribe(consumerSub, DafkaProto.MSG, topic);
        DafkaProto.subscribe(consumerSub, DafkaProto.HEAD, topic);

        if (!this.resetLatest) {
            sendGetHeadsMsg(topic);
        }

        topics.add(topic);
    }

    public void terminate(ZActor actor)
    {
        actor.send("$TERM");
    }

    public static void main(String[] args) throws InterruptedException, ParseException
    {
        Properties consumerProperties = new Properties();
        Options options = new Options();
        options.addOption("from_beginning", "Consume messages from beginning of partition");
        options.addOption("pub", true, "Beacon publisher address");
        options.addOption("sub", true, "Beacon subscriber address");
        options.addOption("verbose", "Enable verbose logging");
        options.addOption("help", "Displays this help");
        CommandLineParser parser = new DefaultParser();
        try {
            final CommandLine cmd = parser.parse(options, args);

            if (cmd.hasOption("help")) {
                HelpFormatter formatter = new HelpFormatter();
                formatter.printHelp("dafka_console_consumer", options);
                return;
            }

            if (cmd.hasOption("verbose")) {
                Configurator.setRootLevel(Level.DEBUG);
            }
            else {
                Configurator.setRootLevel(Level.ERROR);
            }

            if (cmd.hasOption("from_beginning")) {
                consumerProperties.setProperty("consumer.offset.reset", "earliest");
            }
            if (cmd.hasOption("pub")) {
                consumerProperties.setProperty("beacon.pub_address", cmd.getOptionValue("pub"));
            }
            if (cmd.hasOption("sub")) {
                consumerProperties.setProperty("beacon.sub_address", cmd.getOptionValue("sub"));
            }
        }
        catch (UnrecognizedOptionException exception) {
            System.out.println(exception.getMessage());
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp("dafka_console_consumer", options);
            return;
        }

        ZContext context = new ZContext();

        final DafkaConsumer dafkaConsumer = new DafkaConsumer();
        ZActor actor = new ZActor(context, dafkaConsumer, null, Arrays.asList(consumerProperties).toArray());
        // Wait until actor is ready
        Socket pipe = actor.pipe();
        byte[] signal = pipe.recv();
        assert signal[0] == 0;
        dafkaConsumer.subscribe("HELLO");

        final Thread zmqThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                ZMsg msg = actor.recv(100);
                if (msg != null) {
                    String subject = msg.popString();
                    String address = msg.popString();
                    String content = msg.popString();

                    System.out.println(subject);
                    System.out.println(address);
                    System.out.println(content);
                }
            }

            System.out.println("KILL ME!!");
            boolean rc = actor.sign();
            assert (rc);
            dafkaConsumer.terminate(actor);
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Interrupted! Killing dafka console consumer.");
            context.close();
            try {
                zmqThread.interrupt();
                zmqThread.join();
            }
            catch (InterruptedException e) {
            }
        }));

        zmqThread.start();
    }
}
