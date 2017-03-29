package org.mitallast.queue.crdt.commutative;

import com.google.inject.Inject;
import com.typesafe.config.Config;
import org.mitallast.queue.Version;
import org.mitallast.queue.common.component.AbstractComponent;
import org.mitallast.queue.common.stream.Streamable;
import org.mitallast.queue.raft.Raft;
import org.mitallast.queue.raft.discovery.ClusterDiscovery;
import org.mitallast.queue.transport.DiscoveryNode;
import org.mitallast.queue.transport.TransportController;
import org.mitallast.queue.transport.TransportService;
import org.mitallast.queue.transport.netty.codec.MessageTransportFrame;

import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;

public class CmRDTService extends AbstractComponent {

    private final TransportController transportController;
    private final TransportService transportService;
    private final ClusterDiscovery clusterDiscovery;
    private final Raft raft;
    private final LWWRegister register;

    private final ReentrantLock lock = new ReentrantLock();

    @Inject
    public CmRDTService(
        Config config,
        TransportController transportController,
        TransportService transportService,
        ClusterDiscovery clusterDiscovery,
        Raft raft
    ) {
        super(config.getConfig("crdt"), CmRDTService.class);
        this.transportController = transportController;
        this.transportService = transportService;
        this.clusterDiscovery = clusterDiscovery;
        this.raft = raft;

        register = new LWWRegister(this::broadcast);

        transportController.registerMessageHandler(LWWRegister.SourceAssign.class, this::source);
        transportController.registerMessageHandler(LWWRegister.DownstreamAssign.class, this::downstream);
    }

    public Streamable value() {
        return register.value();
    }

    public void assign(Streamable value) {
        source(new LWWRegister.SourceAssign(value));
    }

    private void source(LWWRegister.SourceAssign streamable) {
        logger.info("source update: {}", streamable);
        lock.lock();
        try {
            register.sourceUpdate(streamable);
        } finally {
            lock.unlock();
        }
    }

    private void downstream(LWWRegister.DownstreamAssign streamable) {
        logger.info("downstream update: {}", streamable);
        lock.lock();
        try {
            register.downstreamUpdate(streamable);
        } finally {
            lock.unlock();
        }
    }

    private void broadcast(Streamable message) {
        for (DiscoveryNode discoveryNode : raft.currentMeta().members()) {
            logger.info("broadcast to {}: {}", discoveryNode, message);
            send(discoveryNode, message);
        }
    }

    private void send(DiscoveryNode node, Streamable message) {
        if (node.equals(clusterDiscovery.self())) {
            transportController.dispatch(new MessageTransportFrame(Version.CURRENT, message));
        } else {
            try {
                transportService.connectToNode(node);
                transportService.channel(node).message(message);
            } catch (IOException e) {
                logger.warn("error send message to {}", node, e);
            }
        }
    }
}
