package org.mitallast.queue.raft.state;

import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import org.mitallast.queue.common.settings.Settings;
import org.mitallast.queue.raft.action.append.AppendRequest;
import org.mitallast.queue.raft.action.append.AppendResponse;
import org.mitallast.queue.raft.action.poll.PollRequest;
import org.mitallast.queue.raft.action.poll.PollResponse;
import org.mitallast.queue.raft.action.vote.VoteRequest;
import org.mitallast.queue.raft.action.vote.VoteResponse;
import org.mitallast.queue.raft.cluster.ClusterService;
import org.mitallast.queue.raft.log.entry.RaftLogEntry;
import org.mitallast.queue.raft.util.ExecutionContext;
import org.mitallast.queue.raft.util.Quorum;
import org.mitallast.queue.transport.DiscoveryNode;
import org.mitallast.queue.transport.TransportService;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

class FollowerState extends ActiveState {
    private final Set<DiscoveryNode> committing = new HashSet<>();
    private final Random random = new Random();
    private volatile ScheduledFuture<?> heartbeatTimer;

    @Inject
    public FollowerState(
        Settings settings,
        RaftStateContext context,
        ExecutionContext executionContext,
        TransportService transportService,
        ClusterService clusterService
    ) {
        super(settings, context, executionContext, transportService, clusterService);
    }

    @Override
    public RaftStateType type() {
        return RaftStateType.FOLLOWER;
    }

    @Override
    protected void startInternal() {
        startHeartbeatTimeout();
    }

    /**
     * Starts the heartbeat timer.
     */
    private void startHeartbeatTimeout() {
        executionContext.checkThread();
        logger.debug("starting heartbeat timer");
        resetHeartbeatTimeout();
    }

    /**
     * Resets the heartbeat timer.
     */
    private void resetHeartbeatTimeout() {
        executionContext.checkThread();
        if (!lifecycle().started()) return;

        // If a timer is already set, cancel the timer.
        if (heartbeatTimer != null) {
            logger.debug("reset heartbeat timeout");
            heartbeatTimer.cancel(false);
        }

        // Set the election timeout in a semi-random fashion with the random range
        // being election timeout and 2 * election timeout.
        long delay = context.getElectionTimeout() + (random.nextInt((int) context.getElectionTimeout()) % context.getElectionTimeout());
        heartbeatTimer = executionContext.schedule("heartbeat timeout", () -> {
            heartbeatTimer = null;
            if (lifecycle().started()) {
                context.setLeader(null);
                logger.warn("heartbeat timed out in {} milliseconds, transitioning to candidate", delay);
                try {
                    sendPollRequests();
                } catch (IOException e) {
                    logger.error("error send poll requests");
                    resetHeartbeatTimeout();
                }
            }

        }, delay, TimeUnit.MILLISECONDS);
    }

    @Override
    public CompletableFuture<AppendResponse> append(AppendRequest request) {
        executionContext.checkThread();
        resetHeartbeatTimeout();
        CompletableFuture<AppendResponse> response = super.append(request);
        resetHeartbeatTimeout();
        return response;
    }

    @Override
    protected CompletableFuture<?> applyEntry(RaftLogEntry entry) {
        return super.applyEntry(entry).thenRun(this::replicateCommits);
    }

    private void replicateCommits() {
        clusterService.members().stream()
            .filter(this::isActiveReplica)
            .forEach(member -> {
                try {
                    commit(member);
                } catch (IOException e) {
                    logger.error("error commit replica");
                }
            });
    }

    /**
     * Returns a boolean value indicating whether the given member is a replica of this follower.
     */
    private boolean isActiveReplica(MemberState member) {
        executionContext.checkThread();
        if (member != null && member.getType() == MemberState.Type.PASSIVE) {
            MemberState thisMember = clusterService.member(transportService.localNode());
            int index = thisMember.getIndex();
            int activeMembers = clusterService.activeMembers().size();
            int passiveMembers = clusterService.passiveMembers().size();
            while (passiveMembers > index) {
                if (index % passiveMembers == member.getIndex()) {
                    return true;
                }
                index += activeMembers;
            }
        }
        return false;
    }

    /**
     * Commits entries to the given member.
     */
    private void commit(MemberState member) throws IOException {
        executionContext.checkThread();
        if (member.getMatchIndex() == context.getCommitIndex())
            return;

        if (member.getNextIndex() == 0)
            member.setNextIndex(context.getLog().lastIndex());

        if (!committing.contains(member.getNode())) {
            long prevIndex = getPrevIndex(member);
            RaftLogEntry prevEntry = getPrevEntry(prevIndex);
            List<RaftLogEntry> entries = getEntries(prevIndex);
            commit(member, prevIndex, prevEntry, entries);
        }
    }

    private long getPrevIndex(MemberState member) {
        executionContext.checkThread();
        return member.getNextIndex() - 1;
    }

    private RaftLogEntry getPrevEntry(long prevIndex) throws IOException {
        executionContext.checkThread();
        return context.getLog().getEntry(prevIndex);
    }

    private List<RaftLogEntry> getEntries(long prevIndex) throws IOException {
        executionContext.checkThread();
        long index = Math.max(prevIndex + 1, context.getLog().firstIndex());
        List<RaftLogEntry> entries = new ArrayList<>();
        while (index <= context.getLog().lastIndex()) {
            RaftLogEntry entry = context.getLog().getEntry(index);
            if (entry != null) {
                entries.add(entry);
            }
            index++;
        }
        return entries;
    }

    /**
     * Sends a commit message.
     */
    private void commit(MemberState member, long prevIndex, RaftLogEntry prevEntry, List<RaftLogEntry> entries) {
        executionContext.checkThread();
        AppendRequest request = AppendRequest.builder()
            .setTerm(context.getTerm())
            .setLeader(transportService.localNode())
            .setLogIndex(prevIndex)
            .setLogTerm(prevEntry != null ? prevEntry.term() : 0)
            .setEntries(entries)
            .setCommitIndex(context.getCommitIndex())
            .setGlobalIndex(context.getGlobalIndex())
            .build();

        committing.add(member.getNode());
        logger.debug("sent {} to {}", request, member);

        transportService.client(member.getNode().address()).<AppendRequest, AppendResponse>send(request).whenCompleteAsync((response, error) -> {
            committing.remove(member.getNode());
            if (lifecycle().started()) {
                if (error == null) {
                    logger.debug("received {} from {}", response, member);
                    // If replication succeeded then trigger commit futures.
                    if (response.succeeded()) {
                        updateMatchIndex(member, response);
                        updateNextIndex(member);

                        // If there are more entries to send then attempt to send another commit.
                        if (hasMoreEntries(member)) {
                            try {
                                commit(member);
                            } catch (IOException e) {
                                logger.error("error commit", e);
                            }
                        }
                    } else {
                        resetMatchIndex(member, response);
                        resetNextIndex(member);

                        // If there are more entries to send then attempt to send another commit.
                        if (hasMoreEntries(member)) {
                            try {
                                commit(member);
                            } catch (IOException e) {
                                logger.error("error commit", e);
                            }
                        }
                    }
                } else {
                    logger.warn(error.getMessage());
                }
            }
        }, executionContext.executor("replica append response"));
    }

    /**
     * Returns a boolean value indicating whether there are more entries to send.
     */
    private boolean hasMoreEntries(MemberState member) {
        executionContext.checkThread();
        return member.getNextIndex() < context.getLog().lastIndex();
    }

    /**
     * Updates the match index when a response is received.
     */
    private void updateMatchIndex(MemberState member, AppendResponse response) {
        executionContext.checkThread();
        // If the replica returned a valid match index then update the existing match index. Because the
        // replicator pipelines replication, we perform a MAX(matchIndex, logIndex) to get the true match index.
        member.setMatchIndex(Math.max(member.getMatchIndex(), response.logIndex()));
    }

    /**
     * Updates the next index when the match index is updated.
     */
    private void updateNextIndex(MemberState member) {
        executionContext.checkThread();
        // If the match index was set, update the next index to be greater than the match index if necessary.
        // Note that because of pipelining append requests, the next index can potentially be much larger than
        // the match index. We rely on the algorithm to reject invalid append requests.
        member.setNextIndex(Math.max(member.getNextIndex(), Math.max(member.getMatchIndex() + 1, 1)));
    }

    /**
     * Resets the match index when a response fails.
     */
    private void resetMatchIndex(MemberState member, AppendResponse response) {
        executionContext.checkThread();
        if (member.getMatchIndex() == 0) {
            member.setMatchIndex(response.logIndex());
        } else if (response.logIndex() != 0) {
            member.setMatchIndex(Math.max(member.getMatchIndex(), response.logIndex()));
        }
        logger.debug("reset match index for {} to {}", member, member.getMatchIndex());
    }

    /**
     * Resets the next index when a response fails.
     */
    private void resetNextIndex(MemberState member) {
        executionContext.checkThread();
        if (member.getMatchIndex() != 0) {
            member.setNextIndex(member.getMatchIndex() + 1);
        } else {
            member.setNextIndex(context.getLog().firstIndex());
        }
        logger.debug("reset next index for {} to {}", member, member.getNextIndex());
    }

    @Override
    protected VoteResponse handleVote(VoteRequest request) throws IOException {
        executionContext.checkThread();
        // Reset the heartbeat timeout if we voted for another candidate.
        VoteResponse response = super.handleVote(request);
        if (response.voted()) {
            resetHeartbeatTimeout();
        }
        return response;
    }

    private void sendPollRequests() throws IOException {
        // Set a new timer within which other nodes must respond in order for this node to transition to candidate.
        heartbeatTimer = executionContext.schedule("heartbeat timer", () -> {
            logger.debug("failed to poll a majority of the cluster in {}", context.getElectionTimeout());
            resetHeartbeatTimeout();
        }, context.getElectionTimeout(), TimeUnit.MILLISECONDS);

        // Create a quorum that will track the number of nodes that have responded to the poll request.
        final AtomicBoolean complete = new AtomicBoolean();
        ImmutableList<DiscoveryNode> votingMembers = clusterService.activeNodes();

        // If there are no other members in the cluster, immediately transition to leader.
        if (votingMembers.isEmpty()) {
            logger.debug("single member cluster, transitioning directly to leader");
            context.transition(LeaderState.class);
            return;
        }

        final Quorum quorum = new Quorum((int) Math.floor(votingMembers.size() / 2.0) + 1, (elected) -> {
            // If a majority of the cluster indicated they would vote for us then transition to candidate.
            complete.set(true);
            if (elected) {
                context.transition(CandidateState.class);
            } else {
                resetHeartbeatTimeout();
            }
        });

        // First, load the last log entry to get its term. We load the entry
        // by its index since the index is required by the protocol.
        long lastIndex = context.getLog().lastIndex();
        RaftLogEntry lastEntry = lastIndex != 0 ? context.getLog().getEntry(lastIndex) : null;

        logger.info("polling members {}", votingMembers);
        final long lastTerm = lastEntry != null ? lastEntry.term() : 0;

        // Once we got the last log term, iterate through each current member
        // of the cluster and vote each member for a vote.
        for (DiscoveryNode member : votingMembers) {
            logger.debug("polling {} for next term {}", member, context.getTerm() + 1);
            PollRequest request = PollRequest.builder()
                .setTerm(context.getTerm())
                .setCandidate(transportService.localNode())
                .setLogIndex(lastIndex)
                .setLogTerm(lastTerm)
                .build();

            transportService.client(member.address()).<PollRequest, PollResponse>send(request).whenCompleteAsync((response, error) -> {
                executionContext.checkThread();
                if (!complete.get() && lifecycle().started()) {
                    if (error != null) {
                        logger.warn("error poll request {}", error.getMessage());
                        quorum.fail();
                    } else {
                        if (response.term() > context.getTerm()) {
                            context.setTerm(response.term());
                        }

                        if (!response.accepted()) {
                            logger.debug("received rejected poll from {}", member);
                            quorum.fail();
                        } else if (response.term() != context.getTerm()) {
                            logger.debug("received accepted poll for a different term from {}", member);
                            quorum.fail();
                        } else {
                            logger.debug("received accepted poll from {}", member);
                            quorum.succeed();
                        }
                    }
                }
            }, executionContext.executor("poll response handle"));
        }
    }

    /**
     * Cancels the heartbeat timeout.
     */
    private void cancelHeartbeatTimeout() {
        executionContext.checkThread();
        if (heartbeatTimer != null) {
            logger.debug("cancelling heartbeat timer");
            heartbeatTimer.cancel(false);
        }
    }

    @Override
    protected void stopInternal() {
        executionContext.checkThread();
        cancelHeartbeatTimeout();
    }
}
