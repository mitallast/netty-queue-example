package org.mitallast.queue.crdt.replication;

import com.google.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.mitallast.queue.common.Match;
import org.mitallast.queue.common.stream.Streamable;
import org.mitallast.queue.crdt.CrdtService;
import org.mitallast.queue.crdt.bucket.Bucket;
import org.mitallast.queue.crdt.log.LogEntry;
import org.mitallast.queue.crdt.protocol.AppendEntries;
import org.mitallast.queue.crdt.protocol.AppendRejected;
import org.mitallast.queue.crdt.protocol.AppendSuccessful;
import org.mitallast.queue.raft.discovery.ClusterDiscovery;
import org.mitallast.queue.transport.TransportController;
import org.mitallast.queue.transport.TransportService;

import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;

public class Replica {
    private final static Logger logger = LogManager.getLogger();
    private final ClusterDiscovery clusterDiscovery;
    private final TransportService transportService;
    private final CrdtService crdtService;

    private final ReentrantLock lock = new ReentrantLock();
    private final Match.Handler<Streamable> handler;

    @Inject
    public Replica(
        ClusterDiscovery clusterDiscovery,
        TransportService transportService,
        TransportController transportController,
        CrdtService crdtService
    ) {
        this.clusterDiscovery = clusterDiscovery;
        this.transportService = transportService;
        this.crdtService = crdtService;

        handler = Match.<Streamable>handle()
            .when(AppendEntries.class, this::appendEntries)
            .build();

        transportController.registerMessageHandler(AppendEntries.class, this::handle);
    }

    public void handle(Streamable event) {
        lock.lock();
        try {
            handler.accept(event);
        } catch (IOException e) {
            logger.error(e);
        } finally {
            lock.unlock();
        }
    }

    private void appendEntries(AppendEntries message) throws IOException {
        Bucket bucket = crdtService.bucket(message.bucket());
        if (bucket == null) {
            logger.warn("unexpected bucket {}, ignore", message.bucket());
            return;
        }
        long localVclock = bucket.vclock().get(message.member());
        if (localVclock == message.prevVclock()) {
            logger.debug("append entries vclock:{}", localVclock);
            for (LogEntry logEntry : message.entries()) {
                bucket.registry().crdt(logEntry.id()).update(logEntry.event());
                localVclock = Math.max(localVclock, logEntry.vclock());
            }
            bucket.vclock().put(message.member(), localVclock);
            logger.debug("append entries successful:{}", localVclock);
            transportService.connectToNode(message.member());
            transportService.channel(message.member())
                .send(new AppendSuccessful(message.bucket(), clusterDiscovery.self(), localVclock));
        } else {
            logger.warn("unmatched vclock local:{} remote:{}", localVclock, message.prevVclock());
            transportService.connectToNode(message.member());
            transportService.channel(message.member())
                .send(new AppendRejected(message.bucket(), clusterDiscovery.self(), localVclock));
        }
    }
}
