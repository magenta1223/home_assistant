# Component maintenance storage contract

`ComponentReader` reads centroids and slices; `ComponentMaintenanceStore` adds the trusted ingestion writes.
A Component's centroid and slices cover episodes of every access, so a user-facing caller (Component search) must
keep only the slices the requester can read before anything leaves the use case. Writes are never an
external mutation API. Importing a restricted source may legitimately exclude the importing user
from its audience; this internal boundary does not infer authorization from that user.

A Component stores the unnormalized sum of its episode vectors and their count. Its direction is the
normalized mean, the same as `GroupGeometry.centroid` of the members. An episode belongs to at most one
Component. Slices are not stored: reads group members by each episode's current access, so an access change
on an Episode moves it to another slice without a Component write.

`create` starts a Component with one episode. `join` adds one episode and replaces the centroid only while the
Component still has the expected episode count; otherwise it returns false and changes nothing. A missing
episode, an episode already in a Component, or a storage failure rolls back the whole write. The storage adapter
owns transactions and performs no model calls or changes to Episode, Source, or indexing outbox state.
The Component's episode count is also its vector version for Groups.

## Episode assignment ports

| Port | Contract |
|---|---|
| `ComponentAssignmentOutbox` | Durable assignment queue written with each episode; claims fence stale workers, failed rows stop after the attempt limit, and already-assigned episodes complete on claim |
| `EpisodeEmbeddingGenerator` / `EpisodeEmbeddingStore` | Normalized episode vectors; immutable per episode, content hash, model and format |
| `QueryEmbeddingGenerator` | Question vector from the episode embedding model with the `query: ` prefix, compared with centroids |
| `ComponentDescriptionGenerator` | Writes a short description from the given episode contents only (newest first); the caller passes just the viewer's readable episodes |
| `ComponentDescriptionStore` | Regenerable description cache keyed by Component and the set of slices a viewer reads; stores slice versions beside the text and replaces on save |
