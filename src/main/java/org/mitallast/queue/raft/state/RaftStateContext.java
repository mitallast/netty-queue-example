package org.mitallast.queue.raft.state;

import com.google.inject.Inject;
import org.mitallast.queue.common.concurrent.Futures;
import org.mitallast.queue.common.settings.Settings;
import org.mitallast.queue.common.unit.TimeValue;
import org.mitallast.queue.raft.NoLeaderException;
import org.mitallast.queue.raft.Protocol;
import org.mitallast.queue.raft.Query;
import org.mitallast.queue.raft.action.join.JoinRequest;
import org.mitallast.queue.raft.action.join.JoinResponse;
import org.mitallast.queue.raft.action.leave.LeaveRequest;
import org.mitallast.queue.raft.action.leave.LeaveResponse;
import org.mitallast.queue.raft.cluster.ClusterService;
import org.mitallast.queue.raft.log.RaftLog;
import org.mitallast.queue.raft.log.compaction.Compactor;
import org.mitallast.queue.raft.util.ExecutionContext;
import org.mitallast.queue.transport.DiscoveryNode;
import org.mitallast.queue.transport.TransportService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class RaftStateContext extends RaftStateClient implements Protocol {
    private final RaftStateFactory stateFactory;
    private final RaftState stateMachine;
    private final RaftLog log;
    private final Compactor compactor;
    private final long electionTimeout;
    private final long heartbeatInterval;

    private volatile AbstractState state;
    private volatile ScheduledFuture<?> joinTimer;

    private volatile DiscoveryNode lastVotedFor;
    private volatile long commitIndex;
    private volatile long globalIndex;

    @Inject
    public RaftStateContext(
        Settings settings,
        RaftStateFactory stateFactory,
        RaftState raftState,
        RaftLog log,
        Compactor compactor,
        TransportService transportService,
        ClusterService clusterService,
        ExecutionContext executionContext
    ) throws ExecutionException, InterruptedException {
        super(settings, transportService, clusterService, executionContext);
        this.log = log;
        this.stateMachine = raftState;
        this.compactor = compactor;
        this.stateFactory = stateFactory;
        this.heartbeatInterval = componentSettings.getAsTime("heartbeat_interval", TimeValue.timeValueMillis(100)).millis();
        this.electionTimeout = componentSettings.getAsTime("election_timeout", TimeValue.timeValueMillis(heartbeatInterval * 2)).millis();
        executionContext.submit(() -> {
            transition(StartState.class);
        }).get();
    }


    public long getElectionTimeout() {
        executionContext.checkThread();
        return electionTimeout;
    }

    public long getHeartbeatInterval() {
        executionContext.checkThread();
        return heartbeatInterval;
    }

    @Override
    RaftStateContext setLeader(DiscoveryNode leader) {
        executionContext.checkThread();
        if (this.leader == null) {
            if (leader != null) {
                this.leader = leader;
                this.lastVotedFor = null;
                logger.info("found leader {}", leader);
            }
        } else if (leader != null) {
            if (this.leader != leader) {
                this.leader = leader;
                this.lastVotedFor = null;
                logger.info("found leader {}", leader);
            }
        } else {
            this.leader = null;
        }
        return this;
    }

    RaftStateContext setTerm(long term) {
        executionContext.checkThread();
        if (term > this.term) {
            this.term = term;
            this.leader = null;
            this.lastVotedFor = null;
            logger.info("incremented term {}", term);
        }
        return this;
    }

    public DiscoveryNode getLastVotedFor() {
        executionContext.checkThread();
        return lastVotedFor;
    }

    RaftStateContext setLastVotedFor(DiscoveryNode candidate) {
        executionContext.checkThread();
        // If we've already voted for another candidate in this term then the last voted for candidate cannot be overridden.
        if (lastVotedFor != null && candidate != null) {
            throw new IllegalStateException("Already voted for another candidate");
        }
        if (leader != null && candidate != null) {
            throw new IllegalStateException("Cannot cast vote - leader already exists");
        }
        this.lastVotedFor = candidate;
        if (candidate != null) {
            logger.info("voted for {}", candidate);
        } else {
            logger.info("reset last voted for");
        }
        return this;
    }

    public long getCommitIndex() {
        executionContext.checkThread();
        return commitIndex;
    }

    RaftStateContext setCommitIndex(long commitIndex) {
        executionContext.checkThread();
        if (commitIndex < 0)
            throw new IllegalArgumentException("commit index must be positive");
        if (commitIndex < this.commitIndex)
            throw new IllegalArgumentException("cannot decrease commit index");
        this.commitIndex = commitIndex;
        compactor.setCommitIndex(commitIndex);
        return this;
    }

    public long getGlobalIndex() {
        executionContext.checkThread();
        return globalIndex;
    }

    RaftStateContext setGlobalIndex(long globalIndex) {
        executionContext.checkThread();
        if (globalIndex < 0)
            throw new IllegalArgumentException("global index must be positive");
        this.globalIndex = Math.max(this.globalIndex, globalIndex);
        compactor.setCompactIndex(globalIndex);
        return this;
    }

    public AbstractState raftState() {
        return state;
    }

    public RaftStateType getState() {
        return state.type();
    }

    RaftState getStateMachine() {
        executionContext.checkThread();
        return stateMachine;
    }

    public RaftLog getLog() {
        return log;
    }

    @Override
    protected DiscoveryNode selectMember(Query<?> query) {
        executionContext.checkThread();
        if (!query.consistency().isLeaderRequired()) {
            return transportService.localNode();
        }
        return super.selectMember(query);
    }

    @Override
    protected CompletableFuture<Void> register(List<DiscoveryNode> members) {
        executionContext.checkThread();
        return register(members, Futures.future())
            .thenAcceptAsync(response -> setSession(response.session()), executionContext.executor());
    }

    @Override
    protected CompletableFuture<Void> keepAlive(List<DiscoveryNode> members) {
        executionContext.checkThread();
        return keepAlive(members, Futures.future())
            .thenAcceptAsync(response -> setVersion(response.version()), executionContext.executor());
    }

    public synchronized void transition(Class<? extends AbstractState> state) {
        executionContext.checkThread();
        if (this.state != null && state == this.state.getClass()) {
            return;
        }

        logger.info("transitioning to {}", state.getSimpleName());

        // Force state transitions to occur synchronously in order to prevent race conditions.
        if (this.state != null) {
            this.state.close();
        }
        this.state = stateFactory.create(state);
        this.state.open();
    }

    private CompletableFuture<Void> join() {
        executionContext.checkThread();
        CompletableFuture<Void> future = Futures.future();
        executionContext.execute(() -> join(future));
        return future;
    }

    private CompletableFuture<Void> join(CompletableFuture<Void> future) {
        executionContext.checkThread();
        join(new ArrayList<>(clusterService.nodes()), Futures.future()).whenComplete((result, error) -> {
            executionContext.checkThread();
            if (error == null) {
                future.complete(null);
            } else {
                joinTimer = executionContext.schedule(() -> join(future), heartbeatInterval, TimeUnit.MILLISECONDS);
            }
        });
        return future;
    }

    private CompletableFuture<Void> join(List<DiscoveryNode> members, CompletableFuture<Void> future) {
        executionContext.checkThread();
        if (members.isEmpty()) {
            future.completeExceptionally(new NoLeaderException("no leader found"));
            return future;
        }
        return join(selectMember(members), members, future);
    }

    private CompletableFuture<Void> join(DiscoveryNode member, List<DiscoveryNode> members, CompletableFuture<Void> future) {
        executionContext.checkThread();
        logger.info("joining cluster via {}", member);
        JoinRequest request = JoinRequest.builder()
            .setMember(transportService.localNode())
            .build();
        transportService.client(member.address()).<JoinRequest, JoinResponse>send(request).whenCompleteAsync((response, error) -> {
            if (error == null) {
                setLeader(response.leader());
                setTerm(response.term());
                future.complete(null);
                logger.info("joined cluster");
            } else {
                logger.info("cluster join failed, retrying");
                setLeader(null);
                join(members, future);
            }
        }, executionContext.executor());
        return future;
    }

    private CompletableFuture<Void> leave() {
        executionContext.checkThread();
        return leave(new ArrayList<>(clusterService.nodes()), Futures.future());
    }

    private CompletableFuture<Void> leave(List<DiscoveryNode> members, CompletableFuture<Void> future) {
        executionContext.checkThread();
        if (members.isEmpty()) {
            future.completeExceptionally(new NoLeaderException("no leader found"));
            return future;
        }
        return leave(selectMember(members), members, future);
    }

    private CompletableFuture<Void> leave(DiscoveryNode member, List<DiscoveryNode> members, CompletableFuture<Void> future) {
        executionContext.checkThread();
        logger.info("leaving cluster via {}", member);
        LeaveRequest request = LeaveRequest.builder()
            .setMember(transportService.localNode())
            .build();
        transportService.client(member.address()).<LeaveRequest, LeaveResponse>send(request).whenCompleteAsync((response, error) -> {
            if (error == null) {
                future.complete(null);
                logger.info("left cluster");
            } else {
                logger.info("cluster leave failed, retrying");
                setLeader(null);
                leave(members, future);
            }
        }, executionContext.executor());
        return future;
    }

    private void cancelJoinTimer() {
        executionContext.checkThread();
        if (joinTimer != null) {
            logger.info("cancelling join timer");
            joinTimer.cancel(false);
        }
    }

    @Override
    public void delete() {
        executionContext.checkThread();
        if (log != null) {
            try {
                log.delete();
            } catch (IOException e) {
                logger.error("error delete log", e);
            }
        }
    }

    @Override
    protected void doStop() throws IOException {
        try {
            executionContext.submit(() -> {
                cancelJoinTimer();
                transition(StartState.class);
                leave();
            }).get();
            super.doStop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        } catch (ExecutionException e) {
            throw new IOException(e);
        }
    }

    @Override
    protected void doStart() throws IOException {
        try {
            CompletableFuture<Void> future = Futures.future();
            executionContext.submit(() -> {
                if (settings.getAsBoolean("raft.passive", false)) {
                    transition(PassiveState.class);
                    join().whenComplete((aVoid, error) -> {
                        if (error == null) {
                            future.complete(null);
                        } else {
                            future.completeExceptionally(error);
                        }
                    });
                } else {
                    transition(FollowerState.class);
                    future.complete(null);
                }
            });
            future.thenCompose(aVoid -> asyncStart()).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        } catch (ExecutionException e) {
            throw new IOException(e);
        }
    }
}
