
package org.mitallast.queue.raft.log;

import com.google.common.collect.ImmutableList;
import org.mitallast.queue.raft.Term;
import org.mitallast.queue.raft.protocol.LogEntry;
import org.mitallast.queue.raft.protocol.RaftSnapshot;

import java.util.Optional;

public interface ReplicatedLog {

    ImmutableList<LogEntry> entries();

    int committedEntries();

    long committedIndex();

    boolean containsMatchingEntry(Term otherPrevTerm, long otherPrevIndex);

    Optional<Term> lastTerm();

    long lastIndex();

    long prevIndex();

    long nextIndex();

    ReplicatedLog commit(long committedIndex);

    ReplicatedLog append(LogEntry entry);

    ReplicatedLog append(ImmutableList<LogEntry> entries);

    ReplicatedLog append(ImmutableList<LogEntry> entries, long take);

    ReplicatedLog compactedWith(RaftSnapshot snapshot);

    ImmutableList<LogEntry> entriesBatchFrom(long fromIncluding);

    ImmutableList<LogEntry> entriesBatchFrom(long fromIncluding, int howMany);

    ImmutableList<LogEntry> slice(long from, long until);

    boolean containsEntryAt(long index);

    Term termAt(long index);

    boolean hasSnapshot();

    RaftSnapshot snapshot();
}
