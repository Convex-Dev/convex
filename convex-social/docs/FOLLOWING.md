# Following

The social follow graph stores durable user intent as DID-to-record mappings. A
follow target is a canonical base DID, never an authentication key. This keeps the
graph stable when a `did:web` or `did:convex` user rotates keys.

## Encoding

The following section lives at top-level key `:following`; its arbitrary child map
is named `:follows`:

```text
:following
  :timestamp <collection timestamp>
  :follows
    <target-did>
      :active      true | false
      :timestamp   <record timestamp>
```

## Lattice composition

```text
LWPLattice(
  KeyedLattice(
    :timestamp -> MaxLattice
    :follows   -> MapLattice(LWWLattice(JSONLattice, record timestamp))))
```

The outer LWP chooses the newer following value as the preferred operand and then
delegates to the keyed structure. It does not replace the whole set. Therefore:

- edits to different target DIDs merge independently;
- a later complete record wins when replicas edit the same target;
- feed and profile edits do not compete with following edits;
- missing `:following`, `:follows`, or target entries retain additive map
  semantics.

No custom follow-set lattice is needed. Generic `LWPLattice` validates and sanitises
the foreign operand before either its null-adoption or timestamp-reordering paths,
so malformed values cannot bypass child validation.

Social guards the examined section fields before invoking this typed merge and
retains opaque extension fields around it. Local record edits supply their own
timestamps; the enclosing section is stamped through `StampedCursor`. Incoming
values are never stamped with the receiver's clock.

Exact timestamp ties retain the receiver's current value as specified by CAD024.
Writers obtain timestamps from the component's `Supplier<CVMLong>`. The default
supplier reads wall-clock time; applications may supply a deterministic clock.
The supplier is retained by child views, forks and transaction drafts.

## Follow, unfollow and tombstones

`follow(targetDid)` writes a complete record with `:active true`.
`unfollow(targetDid)` writes a later complete record with `:active false`.

The inactive record is the required per-target tombstone. Deleting the map entry
would allow an older active record, present on another replica, to reappear during a
later structural merge. This retains one winning record per target rather than an
unbounded action log.

## Local resolution caching

Incoming signed values must pass signature verification and the current
`DIDKeyAuthorizer` policy. Receiving a value does not edit or re-sign any local
user's follow records.

Key resolution is local bookkeeping. `DIDKeyAuthorizer.cached(...)` provides a
bounded in-memory cache for an immutable authenticated resolver snapshot; replace
it when that snapshot changes. A persistent materialised cache may instead use
local-only, non-replicated storage. Neither cache belongs in the social follow
graph or advances its timestamps. Existing `:account-key` fields are opaque
extensions and do not grant authority.

## Replication meaning

Only active follows belonging to locally registered social users affect the default
P2P desired set. The node retains the union of:

- its local users;
- explicit operator pins;
- those users' direct active follows.

This is one-hop retention, not transitive graph crawling. Materialisation is shared
across local users, while timeline visibility remains per user. Unfollowing stops
future admission and replication when no other local user or pin still requires the
target. It does not assert deletion of the remote user's public data.

## API example

```java
AString aliceDid = DID.forKey(aliceKey.getAccountKey());
AString bobDid = DID.forKey(bobKey.getAccountKey());

Social alice = node.social(aliceDid, aliceKey);
alice.user(aliceDid).follows().follow(bobDid);
alice.sync();
```

Use `P2PNode.social(did, keyPair)` for a rotating DID with an authorizer configured
for the relevant authenticated state or DID document.
