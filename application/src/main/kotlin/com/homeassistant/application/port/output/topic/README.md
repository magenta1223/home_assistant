# Topic maintenance storage contract

`TopicReader` reads centroids and slices; `TopicMaintenanceStore` adds the trusted ingestion writes.
A Topic's centroid and slices cover memories of every access, so a user-facing caller (Topic search) must
keep only the slices the requester can read before anything leaves the use case. Writes are never an
external mutation API. Importing a restricted source may legitimately exclude the importing user
from its audience; this internal boundary does not infer authorization from that user.

A Topic stores the unnormalized sum of its memory vectors and their count. Its direction is the
normalized mean, the same as `GroupGeometry.centroid` of the members. A memory belongs to at most one
Topic. Slices are not stored: reads group members by each memory's current access, so an access change
on a Memory moves it to another slice without a Topic write.

`create` starts a Topic with one memory. `join` adds one memory and replaces the centroid only while the
Topic still has the expected memory count; otherwise it returns false and changes nothing. A missing
memory, a memory already in a Topic, or a storage failure rolls back the whole write. The storage adapter
owns transactions and performs no model calls or changes to Memory, Source, or indexing outbox state.
The Topic's memory count is also its vector version for Groups.

## Memory assignment ports

| Port | Contract |
|---|---|
| `TopicAssignmentOutbox` | Durable assignment queue written with each memory; claims fence stale workers, failed rows stop after the attempt limit, and already-assigned memories complete on claim |
| `MemoryEmbeddingGenerator` / `MemoryEmbeddingStore` | Normalized memory vectors; immutable per memory, content hash, model and format |
| `QueryEmbeddingGenerator` | Question vector from the memory embedding model with the `query: ` prefix, compared with centroids |
| `TopicDescriptionGenerator` | Writes a short description from the given memory contents only (newest first); the caller passes just the viewer's readable memories |
| `TopicDescriptionStore` | Regenerable description cache keyed by Topic and the set of slices a viewer reads; stores slice versions beside the text and replaces on save |
