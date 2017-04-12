package org.mitallast.queue.crdt.commutative;

import org.mitallast.queue.common.stream.StreamInput;
import org.mitallast.queue.common.stream.StreamOutput;
import org.mitallast.queue.common.stream.Streamable;
import org.mitallast.queue.crdt.replication.Replicator;

import java.io.IOException;
import java.util.Optional;

public class LWWRegister implements CmRDT<LWWRegister> {

    public static class SourceAssign implements SourceUpdate {

        private final Streamable value;

        public SourceAssign(Streamable value) {
            this.value = value;
        }

        public SourceAssign(StreamInput stream) throws IOException {
            this.value = stream.readStreamable();
        }

        @Override
        public void writeTo(StreamOutput stream) throws IOException {
            stream.writeClass(value.getClass());
            stream.writeStreamable(value);
        }

    }

    public static class DownstreamAssign implements DownstreamUpdate {

        private final Streamable value;
        private final long timestamp;

        public DownstreamAssign(Streamable value, long timestamp) {
            this.value = value;
            this.timestamp = timestamp;
        }

        public DownstreamAssign(StreamInput stream) throws IOException {
            this.value = stream.readStreamable();
            this.timestamp = stream.readLong();
        }

        @Override
        public void writeTo(StreamOutput stream) throws IOException {
            stream.writeClass(value.getClass());
            stream.writeStreamable(value);
            stream.writeLong(timestamp);
        }

    }

    private final long id;
    private final Replicator replicator;

    private Streamable value = null;
    private long timestamp = 0;

    public LWWRegister(long id, Replicator replicator) {
        this.id = id;
        this.replicator = replicator;
    }

    @Override
    public void update(Streamable event) throws IOException {
        if (event instanceof SourceUpdate) {
            sourceUpdate((SourceUpdate) event);
        } else if (event instanceof DownstreamUpdate) {
            downstreamUpdate((DownstreamUpdate) event);
        }
    }

    @Override
    public boolean shouldCompact(Streamable event) {
        return event instanceof DownstreamAssign &&
            ((DownstreamAssign) event).timestamp < timestamp;
    }

    @Override
    public void sourceUpdate(SourceUpdate update) throws IOException {
        if (update instanceof SourceAssign) {
            assign(((SourceAssign) update).value);
        }
    }

    @Override
    public void downstreamUpdate(DownstreamUpdate update) throws IOException {
        if (update instanceof DownstreamAssign) {
            DownstreamAssign set = (DownstreamAssign) update;
            synchronized (this) {
                if (set.timestamp > timestamp) {
                    value = set.value;
                    timestamp = set.timestamp;
                }
            }
        }
    }

    public void assign(Streamable value) throws IOException {
        synchronized (this) {
            this.value = value;
            this.timestamp = System.currentTimeMillis();
        }
        replicator.append(id, new DownstreamAssign(value, timestamp));
    }

    public Optional<Streamable> value() {
        return Optional.ofNullable(value);
    }
}