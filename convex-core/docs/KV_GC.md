# KV collection and replica cutoffs

`LatticeKV.gc(cutoff)` advances a replicated collection timestamp. `gc()` uses
the component's clock. Both return the number of physically removed entries;
repeating the same cutoff or supplying an older one does nothing.

Collection removes tombstones whose write timestamp is at or before the cutoff.
It also removes expired entries whose expiry and write timestamps are both at
or before it. It retains live records, preserving their values, expiry and
logical write timestamps.

The cutoff is a replication boundary. A replica rejects incoming entries at or
before it unless a collection at that cutoff or later explicitly retained them.
This includes previously unseen writes from an offline replica. New local writes
must use timestamps strictly later than the cutoff; otherwise they fail without
changing state. Choose the cutoff according to the application's offline-write
and retention policy. It is not a physical durability checkpoint.

## State and merge

Uncollected stores retain the legacy flat `Index` of string keys to entries.
After collection, a store is `[entries cutoff]`, where `entries` is that index
and `cutoff` is a `CVMLong`. Store cursors therefore hold `ACell`; use
`kv.entries()` to inspect entries and `kv.getGCTimestamp()` to inspect the cutoff.
Replicate the complete `kv.cursor().get()` value, including its cutoff.

Ordinary entries remain `[value type utime expire]`. Collection adds an optional
fifth field, `retainedAt`, to old records it keeps. This records that the record
survived collection without changing `utime`. Structural entry merges preserve
the maximum retention timestamp of their inputs.

Store merge takes the maximum cutoff and removes entries from either input
whose `utime` is at or before it and whose `retainedAt` is absent or earlier.
It then applies the ordinary entry merge rules. With no cutoff, the legacy rules
apply. Normal merges remain differential; crossing a newer cutoff scans the
older snapshot to filter stale entries.

The cutoff applies before comparing keys, including keys present on both sides.
Allowing an old update solely because a key currently exists would make the
result depend on merge order: another replica may have already discarded that
update. Retention timestamps let established live data survive this rule.

Receiving a partial entry cannot establish a collection boundary or prove
absence, so `KVStoreLattice` requires complete-store delivery. Local component
operations update individual entries atomically. Signed replicas carry
the complete store inside the owner's signed database map.

## Compatibility

Upgrade all replicas before calling GC with this version. Legacy flat stores
remain readable and mergeable, but older software cannot interpret a collected
store's envelope or enforce its cutoff. Do not downgrade collected stores by
exporting only their entries: that loses deletion history.

This extends the flat-store format in CAD037 and requires the corresponding
specification update for the cutoff and retention fields. CAD024 still governs
ordinary entry merge and its directional preference for exact timestamp ties.
