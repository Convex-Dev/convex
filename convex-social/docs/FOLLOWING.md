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
      :account-key <last validated 32-byte Ed25519 key, optional>
```

The cached `:account-key` is an authentication optimisation, not identity. CAD3 may
decode it as a canonical `Blob`; readers therefore use `AccountKey.parse` rather
than relying on the JVM subtype.

## Lattice composition

```text
StampingLattice(
  LWPLattice(
    KeyedLattice(
      :timestamp -> MaxLattice
      :follows   -> MapLattice(
                      StampingLattice(
                        LWWLattice(JSONLattice, record timestamp))))))
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

Exact timestamp ties retain the receiver's current value as specified by CAD024.
Writers obtain timestamps from `LatticeContext`, not directly from the system clock.

## Follow, unfollow and tombstones

`follow(targetDid)` writes a complete record with `:active true`.
`unfollow(targetDid)` writes a later complete record with `:active false`.

The inactive record is the required per-target tombstone. Deleting the map entry
would allow an older active record, present on another replica, to reappear during a
later structural merge. This retains one winning record per target rather than an
unbounded action log.

## Validated signer cache

When a complete signed value arrives for a desired target DID:

1. verify the `SignedData` signature;
2. compare its signer with the last validated key cached for that DID;
3. if it matches, reuse the binding;
4. if it differs or is absent, resolve the DID through `DIDKeyAuthorizer`;
5. reject the owner value if resolution fails;
6. cache a successful changed binding while preserving the current `:active` value.

The cache is part of the target's whole LWW record. Updating it must begin from the
current record so it cannot accidentally reverse a concurrent follow or unfollow.
For `did:key`, the DID itself supplies the immutable key. For rotating methods,
resolution is performed only when the signer changes, subject to the authorizer's
authenticated snapshot/cache policy.

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
