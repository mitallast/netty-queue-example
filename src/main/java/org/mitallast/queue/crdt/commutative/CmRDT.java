package org.mitallast.queue.crdt.commutative;

import org.mitallast.queue.common.stream.Streamable;
import org.mitallast.queue.crdt.Crdt;

import java.io.IOException;

public interface CmRDT<T extends CmRDT<T>> extends Crdt {

    interface SourceUpdate extends Streamable {}

    interface DownstreamUpdate extends Streamable {}

    void sourceUpdate(SourceUpdate update) throws IOException;

    void downstreamUpdate(DownstreamUpdate update) throws IOException;
}