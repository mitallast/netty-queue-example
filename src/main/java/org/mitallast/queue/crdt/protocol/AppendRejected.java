package org.mitallast.queue.crdt.protocol;

import org.mitallast.queue.common.stream.StreamInput;
import org.mitallast.queue.common.stream.StreamOutput;
import org.mitallast.queue.common.stream.Streamable;
import org.mitallast.queue.transport.DiscoveryNode;

import java.io.IOException;

public class AppendRejected implements Streamable {
    private final int bucket;
    private final DiscoveryNode member;
    private final long vclock;

    public AppendRejected(int bucket, DiscoveryNode member, long vclock) {
        this.bucket = bucket;
        this.member = member;
        this.vclock = vclock;
    }

    public AppendRejected(StreamInput stream) throws IOException {
        bucket = stream.readInt();
        member = stream.readStreamable(DiscoveryNode::new);
        vclock = stream.readLong();
    }

    @Override
    public void writeTo(StreamOutput stream) throws IOException {
        stream.writeInt(bucket);
        stream.writeStreamable(member);
        stream.writeLong(vclock);
    }

    public int bucket() {
        return bucket;
    }

    public DiscoveryNode member() {
        return member;
    }

    public long vclock() {
        return vclock;
    }
}