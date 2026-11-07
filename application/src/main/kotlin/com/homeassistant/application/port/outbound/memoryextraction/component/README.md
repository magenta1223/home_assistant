# Component maintenance storage contract

`ComponentRepository` finds Components by an access condition or not yet in any Group, and saves the trusted
ingestion write.
Every access condition runs in the storage query, as the `EpisodeAccess` rules state it, so no caller filters what it
has already read. Writes are never an external mutation API.
Importing a restricted source may legitimately exclude the importing user from its audience; this internal boundary
does not infer authorization from that user.

A Component is one long-term statement extracted from one source episode and never changes. Its access is not
stored: reads take the source episode's current access, so an access change on an Episode needs no Component write.

`save` marks the episode as extracted and stores its statements as Components in one write, even when there
are none. A missing episode, a second extraction of the same episode, or a storage failure rolls back the whole write.
The storage adapter owns transactions and performs no model calls or changes to Episode, Source, or indexing outbox
state. A statement's fingerprint is its vector version for Groups.

## Episode extraction ports

| Port | Contract |
|---|---|
| `ComponentAssignmentOutbox` | Durable extraction queue written with each episode; claims fence stale workers, failed rows stop after the attempt limit, and already-extracted episodes complete on claim |
| `ComponentKnowledgeExtractor` | Long-term statements of an episode that the given known statements do not already state; empty when there is none |
