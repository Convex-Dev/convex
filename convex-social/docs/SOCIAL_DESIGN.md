# Social Lattice Design

Convex Social is a public, lattice-native application region hosted by
`convex-p2p`. Social identities are canonical base DIDs. The Ed25519 key in a
`SignedData` value is an authentication method for that DID, not the user's
durable identifier, and it is independent of the node's P2P transport key.

CAD024 governs lattice composition and signed ownership. CAD043, CAD038 and
CAD004 govern DID resolution, owner authorisation and key rotation.

## Root shape

```text
:social
  <owner-did> -> SignedData<SocialValue>

SocialValue
  :feed      -> IndexLattice<post-key, LWW post>
  :profile   -> LWW profile
  :following -> stamped LWP following section
```

Only canonical base DIDs are accepted as social owners. `did:key` is
self-certifying. Indirect DIDs such as `did:convex` and `did:web` require an
explicit, fail-closed `DIDKeyAuthorizer`; they are never admitted through the
generic compatibility fallback.

`DIDKeyAuthorizer` is the shared key-validation infrastructure. It supports:

- `did:key` directly;
- numeric `did:convex` against a pinned Convex state;
- `did:web` and other authenticated DID documents through `alsoKnownAs`
  `did:key` aliases;
- bounded caching of resolved snapshot bindings.

A `did:key` identity cannot rotate: losing the private key means creating a new
social user. Use `did:convex` or `did:web` when key rotation is required.

## Signed structural merge

Each owner has one signed social value, but that value is not a whole-value LWW
register. Feed, profile and following merge independently. Concurrent edits from
separate authorised devices therefore merge structurally. An explicit owner-aware
signer re-signs a synthesised merged value; a relay without an authorised owner
key can retain an existing valid version but cannot forge a synthesis.

The following encoding and merge rules are specified in
[FOLLOWING.md](FOLLOWING.md).

New post keys contain an eight-byte big-endian timestamp followed by eight random
bytes. Distinct devices can post at the same millisecond without allocating the
same key. Identity and record time are selected once per post operation, before
the atomic update, and retained on retries. Existing eight-byte timestamp-only
keys remain readable, including reply references and timelines. Explicit salted
keys can be constructed with `SocialPost.createKey(timestamp, salt)`.

Merge guards the container shapes of sections it examines. Unfamiliar sections
are retained as opaque values; unknown fields are carried without interpretation.
An incompatible incoming section does not replace an established section, and
other compatible sections can still advance. Signature and DID authorisation
checks remain mandatory. Timeline readers skip unfamiliar keys and post values.

Timestamp ties for revisions of the same record still prefer the established
value. Salting distinguishes post identities; it does not change revision ordering.

Timelines use descending `(postKey, author DID)` order, comparing key bytes in the
same order as the feed index. Continue a page with its last entry's `position()`:

```java
var first = SocialHelpers.buildTimeline(feeds, null, 20);
if (!first.isEmpty()) {
    var next = SocialHelpers.buildTimeline(feeds, first.getLast().position(), 20);
}
```

The exclusive bound includes the full key and author, so simultaneous posts and
identical keys belonging to different authors are not skipped or repeated.
`new TimelinePosition(keyOrPrefix)` omits the author and excludes the whole key
or prefix group. `TimelinePosition.atTimestamp(time)` supplies the eight-byte
timestamp prefix; the existing timestamp overload keeps this coarse behaviour
(zero means unbounded). A bound need not identify an existing entry. Reusing the
same immutable feeds gives stable pagination; changing feeds between requests
does not imply snapshot isolation.

## Default replication policy

For local social DIDs `L`, operator pins `P`, and each local user's direct active
follows `F(u)`, the desired owner set is:

```text
desired = L union P union (union F(u) for u in L)
```

The expansion is deliberately one hop. A followed user's own follows do not
recursively expand the set.

`P2PNode` applies the desired set at both publication and inbound admission. It
bootstraps only `:p2p`, `:id`, and complete signed `[:social <desired-did>]`
owner slots. It does not pull a peer's complete root. An unsolicited social owner
outside the desired set is rejected and is not added to the published replica.
Previously stored unreachable Etch cells may remain until normal store reclamation;
logical retention is defined by the projected root.

Every admitted owner slot must:

1. be a complete value within the configured inbound size bound;
2. contain a signed social index, without requiring known schemas for every field;
3. have a valid Ed25519 signature;
4. use a signer authorised for the path DID.

The policy checks the current `DIDKeyAuthorizer` authority view and rejects the
value on failure. A remembered key cannot override rotation or revocation.
An authoriser may cache bindings for an immutable authenticated snapshot, refreshed
outside merge callbacks. Unfamiliar fields remain covered by the original signature.

## Network trust boundary

P2P data is public, so an operator may assign public inbound connections to a
served lattice view. Assignment is not authentication and does not make the socket
an outbound gossip route.

Unverified connections may submit only complete `LATTICE_VALUE` messages. They
cannot stage unsolicited `DATA` or trigger missing-cell acquisition. The complete
value is checked by path-aware admission before it is persisted or merged.
`DATA_REQUEST` responses remain correlated to the requesting connection and ID.

An inbound connection is upgraded to an authenticated outbound route only after:

- a valid signed NodeInfo establishes the claimed node key and the bounded desired
  peer policy admits it; and
- live challenge/response proves possession of that key.

The challenge has a cryptographically random nonce, the responder key as audience,
and the `convex-lattice-peer-v1` context. The response signs the same nonce, the
challenger key as audience, and the same context. Both signatures are verified.

`NodeConfig.maxConnections` bounds sockets accepted by the shared listener, while
`LatticePropagatorConfig.maxDesiredPeers` bounds configured plus discovery-driven
peer intent for each propagation group (both default to 256). Group message,
queue, acquisition and rejection limits provide the remaining generic resource
bounds.

## Current correctness coverage

Tests cover:

- `did:key`, pinned `did:convex`, and authenticated `did:web` authorisation;
- DID/key separation and rejection of a wrong social signer;
- disjoint follow edits, same-target LWW and cached-key encoding;
- Alice, Bob and Carol with different direct-follow sets;
- Dave joining late through one rendezvous node without an inbound listener;
- authenticated reverse-route upgrade on the original full-duplex socket;
- rejection of forged complete social data from a public unverified connection;
- rejection of unsolicited `DATA` and incomplete unverified values;
- the desired-peer bound.

Network tests bind port `0` and wait on lifecycle or publication signals, never on
elapsed-time sleeps.
