# Topic maintenance storage contract

`TopicMaintenanceStore` is a trusted internal ingestion/maintenance port. Its reads include full,
potentially mixed-ACL descriptions and memberships. They must not be used for user-facing retrieval,
answer context, or an external mutation API. Importing a restricted source may legitimately exclude
the importing user from its audience; this internal boundary does not infer authorization from that user.

`apply` atomically creates topics, adds memberships, adds undirected relations, and replaces listed
descriptions. Existing memberships are never replaced, so additions cannot discard hidden members. A creation key identifies the same
creation across retries; a different description for an existing key rejects the batch. A `Created`
reference must name a creation declared in the batch. Results map those keys to persisted IDs.

All referenced topics and memories must exist. Invalid references, self-relations, conflicting keys, or
storage failure roll back the entire batch. Repeated memberships and reverse relations are idempotent;
cycles are allowed. Reads return existing topics only, and relation reads include either endpoint.
Membership ordering is not a ranking contract. The storage adapter owns transactions and performs no
model calls or changes to Memory, Source, or indexing outbox state.

This port only persists an explicitly supplied batch. Automatic organization uses the separate ports below.

## Memory assignment ports

| Port | Contract |
|---|---|
| `TopicAssignmentOutbox` | Durable assignment queue written with each memory; claims fence stale workers, failed rows stop after the attempt limit, and already-assigned memories complete on claim |
| `TopicCatalog` | Every Topic ID and description, for candidate search |
| `MemoryEmbeddingGenerator` / `MemoryEmbeddingStore` | Normalized memory vectors; immutable per memory, content hash, model and format |
| `TopicAssignmentGenerator` | Model decision: candidate Topics to join with category and revised description, or new Topics |

`TopicWriteBatch.descriptionUpdates` replaces descriptions of existing Topics in the same transaction as
the new memberships; a missing Topic rolls back the batch. Assignment reads include mixed-ACL content and stay internal.
