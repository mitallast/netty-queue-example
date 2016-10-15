package org.mitallast.queue.raft2;

import com.google.common.collect.ImmutableSet;
import org.mitallast.queue.transport.DiscoveryNode;

public class StableClusterConfiguration implements ClusterConfiguration {

    private final long sequenceNumber;
    private final ImmutableSet<DiscoveryNode> members;

    public StableClusterConfiguration(long sequenceNumber, ImmutableSet<DiscoveryNode> members) {
        this.sequenceNumber = sequenceNumber;
        this.members = members;
    }

    @Override
    public long sequenceNumber() {
        return sequenceNumber;
    }

    @Override
    public ImmutableSet<DiscoveryNode> members() {
        return members;
    }

    @Override
    public int quorum() {
        return members.size() / 2 + 1;
    }

    @Override
    public boolean isNewer(ClusterConfiguration state) {
        return sequenceNumber > state.sequenceNumber();
    }

    @Override
    public boolean isTransitioning() {
        return false;
    }

    @Override
    public ClusterConfiguration transitionTo(ClusterConfiguration newConfiguration) {
        return new JointConsensusClusterConfiguration(sequenceNumber, members, newConfiguration.members());
    }

    @Override
    public ClusterConfiguration transitionToStable() {
        return this;
    }

    @Override
    public boolean containsOnNewState(DiscoveryNode member) {
        return members.contains(member);
    }
}
