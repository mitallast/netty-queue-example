package org.mitallast.queue.raft.cluster;

import org.mitallast.queue.common.stream.StreamInput;
import org.mitallast.queue.common.stream.StreamOutput;
import org.mitallast.queue.common.stream.Streamable;
import org.mitallast.queue.transport.DiscoveryNode;

import java.io.IOException;

public class RaftMemberRemoved implements Streamable {
    private final DiscoveryNode member;
    private final int keepInitUntil;

    public RaftMemberRemoved(StreamInput stream) throws IOException {
        member = stream.readStreamable(DiscoveryNode::new);
        keepInitUntil = stream.readInt();
    }

    public RaftMemberRemoved(DiscoveryNode member, int keepInitUntil) {
        this.member = member;
        this.keepInitUntil = keepInitUntil;
    }

    public DiscoveryNode getMember() {
        return member;
    }

    public int getKeepInitUntil() {
        return keepInitUntil;
    }

    @Override
    public void writeTo(StreamOutput stream) throws IOException {
        stream.writeStreamable(member);
        stream.writeInt(keepInitUntil);
    }
}
