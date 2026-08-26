# Social Lattice Design

A peer-to-peer social network built from composable Convex lattice types. Each social
owner has a structurally mergeable value beneath an authenticated signing boundary.

> The current Java implementation uses `AccountKey` for identity and lacks automated
> follow-driven replication. The target changes owners and follow targets to DIDs and
> adds a top-level `:following` LWP section containing the per-target `:follows` map.

Normative basis: CAD024 governs the lattice composition and signing boundary; CAD043,
CAD038 and CAD004 govern DID resolution, owner authorisation and key rotation.

## Current Status

Local follow lists work. `Follows.follow()`, `Follows.unfollow()` and
`Follows.getActive()` update and read a `MapLattice` with per-target LWW records. The
current inactive record already prevents an older active value from resurrecting.

Following does not yet control replication:

- owners and targets are `AccountKey`, not DID;
- the follow map has no outer LWP section stamp;
- `Follows` reads `System.currentTimeMillis()` instead of the `LatticeContext` clock;
- follow records do not cache a validated target signer;
- `SocialHelpers.computeFollowSet()` has no production caller;
- `SocialHelpers.buildTimeline()` only merges feeds supplied by its caller;
- `P2PNode` has no follow-aware inbound policy, so full-root push or pull may ingest all
  social owners offered by a peer.

`P2PSocialSyncTest` is a two-node smoke test for the current implementation. It proves
signed full-root convergence, not DID auth or follow-directed acquisition.

## Architecture

```text
Root KeyedLattice
  :social -> OwnerLattice<SocialLattice>
    <owner-did> -> SignedData<SocialValue> (authorised AccountKey)

SocialValue
  :feed     -> IndexLattice<post-key, LWW post record>
  :profile  -> profile lattice
  :following -> stamped LWP following section

Following section
  :timestamp -> collection write timestamp
  :follows   -> MapLattice<target-did, LWW FollowRecord>

FollowRecord
  :timestamp        -> target-record edit timestamp
  :active           -> boolean
  :account-key      -> last validated target signer, optional
  :resolver-version -> authenticated resolution reference, optional
  :validated-at     -> validation time, optional
  :valid-until      -> freshness bound, optional
```

The `SocialLattice` merges its three sections independently. It is not a whole-value LWW
register, and it is not an application action log.

## DID Owner Boundary

The social subject is a canonical base DID stored as `AString`. The concrete
`AccountKey` in `SignedData` is an authentication method authorised for that DID, not the
durable social identifier. The P2P node key is a separate transport/operator identity.

The target API should use `DID` for users, follow targets, reply authors and timeline
authors. DID URLs with paths, queries or fragments are rejected as owner keys. Do not
silently identify two stored keys through `alsoKnownAs`.

One fail-closed standard Convex owner verifier handles all supported methods:

- `did:key` decodes to one immutable Ed25519 key;
- numeric `did:convex` resolves against pinned CVM account state;
- named `did:convex` resolves through authenticated CNS/registry state;
- `did:web` resolves an authenticated DID document.

An authenticated `did:key` in `alsoKnownAs` may prove the current signer for a
`did:convex` or `did:web` subject. It does not change the social owner path or rewrite
follow targets. A lost `did:key` requires creation of a new social user; users wanting
key rotation should choose `did:convex` or `did:web`.

The current `P2PNode` context does not install a fail-closed indirect-owner verifier.
Changing owner map keys to DIDs without adding that verifier would be unsafe.

### Structural merge beneath the signature

`OwnerLattice` keeps one signed social value per DID. A merge may select one existing
signed value, or its child `SocialLattice` may synthesise a structural combination.
`SignedLattice` publishes a synthesis only when `LatticeContext.signAs(ownerDid, value)`
can sign it with a key authorised for that DID.

Consequently, multiple authorised owner devices may fork, edit different fields, merge
their correctly signed states and re-sign the result. A relay without an owner key cannot
forge the combined state; it retains an existing signed version until an authorised owner
device republishes a merge. There is no requirement for one serial writer.

## Following Section

### Composition

The target `:following` child is conceptually:

```text
StampingLattice(
  LWPLattice(
    KeyedLattice(
      :timestamp -> Max/prefer-newer value
      :follows   -> MapLattice(
                      StampingLattice(LWWLattice(FollowRecord)))
    ),
    Following::timestamp
  ),
  Following::withTimestamp
)
```

LWP makes the collection with the higher timestamp the preferred `own` operand, then
delegates to the inner keyed structure. It does not choose one complete set. The
`:follows` map therefore preserves distinct targets from both replicas, while its LWW
child selects the complete later record when both sides edit the same target.

No custom following-set lattice is needed for this merge. `KeyedLattice` handles the
fixed wrapper fields and `MapLattice` handles arbitrary DID keys. Missing `:follows`
children, missing target keys and a wholly absent `:following` value have the desired
additive semantics.

Foreign validation is distinct from missing-value merge semantics. The current LWP null
fast path adopts `other` directly, and timestamp preference may reorder a newer foreign
operand into the inner `own` position. Validate the complete following value at or above
the LWP boundary, or harden generic LWP adoption/validation; do not assume every foreign
child will be visited as the inner `other` operand. This concern still does not justify a
custom social merge lattice.

This gives the desired separation:

- Alice following Bob and Carol on separate devices merges to both records;
- concurrent follow/unfollow of Bob resolves by Bob's record timestamp;
- updating Bob's cached signer touches Bob's record, not Carol's;
- updating the follow collection does not replace feed or profile state;
- a newer feed or profile update does not replace the follow collection.

Both the changed record and containing follow collection use a timestamp supplied by
`LatticeContext`. One logical update should reuse one context timestamp at both levels.
Application code must not call `System.currentTimeMillis()` or ratchet from stored state.
Distinct exact-timestamp conflicts retain the receiver's own/current value as specified
by CAD024; a later non-tied write resolves them.

### Follow and unfollow

`follow(targetDid)` writes the complete target record with `:active true`.
`unfollow(targetDid)` writes a later complete record with `:active false`. The readable
follow set is the set of target map keys whose winning record is active.

The inactive record is intentionally retained. Because LWP delegates to `MapLattice`, a
map key present only in an older replica survives structural merge. Removing Bob's entry
would therefore allow the older active record to return. The inactive LWW record is the
minimal per-target tombstone; no global action history is required.

### Cached target signer

A follow record may cache the most recently validated `AccountKey` for its target DID,
together with a resolver version and freshness metadata. For an incoming Bob owner value:

1. verify its `SignedData` signature;
2. require the path owner to be Bob's canonical DID;
3. reuse the cached key only if it matches the signer and remains acceptable under the
   resolver policy;
4. otherwise resolve Bob through standard Convex auth;
5. if valid, write a newer Bob follow record containing the binding while preserving
   its current `:active` value;
6. reject or quarantine the value if resolution fails.

Cache refresh and follow intent share one atomic LWW record, so refresh code must always
start from the current merged record. It must not apply a stale copy which could reverse
a concurrent follow/unfollow. Whether all freshness fields should be portable signed data
or partly operator-local state remains an implementation decision.

## Feed

The current feed is an `IndexLattice<Blob, ACell>` with LWW post records. Distinct post
keys union; a later edit/delete record for one post wins. The current 8-byte big-endian
millisecond key sorts chronologically but can collide across authorised devices. The
target should use a stable composite order key containing display time plus a unique
content/device component.

A post record contains:

| Key | Type | Required | Description |
|---|---|---|---|
| `:text` | AString | Yes | Post text |
| `:timestamp` | CVMLong | Yes | Display/edit timestamp |
| `:reply-to` | Blob | No | Parent post key |
| `:reply-did` | AString | No | Canonical DID of the parent author |
| `:media` | AVector\<Hash\> | No | Content references in `:data` |
| `:tags` | ASet\<AString\> | No | Hashtags |
| `:deleted` | CVMLong | No | LWW deletion marker |

Timeline construction filters winning records with `:deleted`. As with unfollow, the
marker prevents an older live version from returning.

## Profile

The current profile is one LWW record containing fields such as `:name`, `:bio`,
`:avatar` and `:url`. If independent concurrent field edits are required, profile can use
the same lattice-native pattern as follows: LWP around a structural map with LWW field
records. That decision is independent of the follow design.

## Follow-Driven Replication

Following, visibility, admission and retention are separate policies:

| Term | Meaning |
|---|---|
| Desired | Owners the node currently intends to fetch |
| Materialised | Owner values present in the readable replica cache |
| Visible | Owners included in one local user's timeline |
| Admitted | Incoming paths permitted to merge |
| Served | Owner values exposed to a remote query or push view |
| Retained | Cells still physically reachable in storage |

For local social DIDs `L`, operator pins `P`, and active follows `F(u)`:

```text
desired = L union P union (union F(u) for u in L)
timeline(u) = {u} union F(u)
```

The desired union shares materialisation across local users; timeline selection remains
per user. Only the desired set should drive remote acquisition. Following is one hop; do
not recursively fetch every followed user's follows.

### Pull unit and controller

The initial safe pull unit is one complete signed owner slot:

```java
nodeServer.pullPath(peer, Social.KEY_SOCIAL, followedDid);
```

Pulling below the signed `:value` boundary would return unsigned state. A
`SocialReplicationController` in `convex-p2p` should own:

- local DID and operator-pin sets;
- the last desired set and each local user's visible set;
- coalesced in-flight `(owner, peer)` pulls;
- the standard DID resolver snapshot and validated target-key cache;
- peer selection, failover and bounded retry;
- last-seen owner hashes and refresh state;
- owner, byte, concurrency and rate limits.

After a local follow/unfollow write and root `sync()`:

1. derive active follows from the merged LWP/LWW following value;
2. recompute the desired set;
3. schedule newly desired owners immediately;
4. pull each owner path from a suitable peer;
5. wait on the returned future for acquisition, merge and publication;
6. retry another peer on absence or failure;
7. stop refreshing owners which leave the desired set.

Do not use full-root `NodeServer.pull()` for steady-state social replication.

### Inbound and outbound policy

Selective ingestion also requires path-aware inbound admission. `LatticeFilter` only
projects outbound data and `serveAllInbound()` exposes the full primary view. Production
policy must:

- accept social values only at `[:social <owner-did>]` for an owner in the desired set;
- validate the signature and DID-to-signer authorisation before merge;
- use a cached target key only under its resolver-version/freshness policy;
- reject unsolicited full-root social values;
- keep P2P infrastructure regions under their own policy;
- bind subsequent `DATA_REQUEST`s to the selected propagator store;
- apply size, rate and concurrency limits before expensive acquisition.

A remote follow claim never grants access. Outbound serving is separate: a filtered
propagator with its own store may expose the chosen public owner view. Pull is simpler
than constructing a personalised push propagator for each remote peer.

### Unfollow and retention

Previously acquired owner state is materialisation, not follow intent. Unfollow removes
the owner from the timeline and desired set immediately, stops refresh, and causes later
unsolicited values for that owner to be rejected when no other local user or pin requires
it.

The inactive LWW follow record remains in the author's signed `:following` section. The
followed owner's already acquired social slot belongs to a separate materialisation cache:

```text
authored social root   durable signed state for local DIDs
replica cache root     evictable signed slots for desired remote owners
read view              lattice union of authored state and cache
served view            explicit operator projection
```

After a grace period, an operator may remove an undesired owner from the cache root.
Cache eviction must never be broadcast as deletion of somebody else's state. Reclaim
unreachable cells only through a safe Etch GC/cutover procedure. A first milestone may
provide logical trimming only: stop refresh and exclude the owner from timelines while
old cells remain physically retained.

## Timeline Construction

Build a user's timeline from the feeds of `{user} union activeFollows(user)`:

1. reverse-iterate every selected feed from a stable pagination cursor;
2. maintain a max-heap of `(display-time, post-key, author, entry)` cursors;
3. emit up to the requested limit, advancing only the selected feed;
4. filter deleted posts and use `(display-time, post-key)` as the next cursor.

This is `O(N log K)` for page size `N` and `K` feeds. Materialisation may be shared by
several local users, but timeline visibility remains per user.

## Current and Target Components

| Component | Current | Target |
|---|---|---|
| Owner key | `AccountKey` | Canonical DID `AString` with standard owner verifier |
| Owner value | Whole `SignedData<SocialLattice>` | Same structural signing boundary, authorised by DID |
| `SocialLattice` | Independent feed/profile/follow child merge | Retained with `:following` child |
| Following | Top-level `:follows` map of `AccountKey` LWW records | Stamped LWP `:following` section containing a DID-keyed `:follows` map |
| Follow record | Timestamp and active flag | Adds validated target key/version cache |
| Follow clock | `System.currentTimeMillis()` | `LatticeContext` timestamp |
| Replication | Full-root smoke sync; owner pull primitive exists | Desired-owner controller and inbound admission |

No custom following lattice is required for merging: `KeyedLattice` can route
`:timestamp` to `MaxLattice` and `:follows` to the per-target map. The likely new
implementation pieces are DID-aware follow record validation, standard DID resolver/cache
integration and `SocialReplicationController`.
Social remains an opt-in application region rather than part of `Lattice.ROOT`.

## Design Decisions

### Why LWP around the follow set?

LWP preserves lattice structure. It makes the newer collection preferred for unresolved
conflicts and then delegates, so unique target records from older and newer values both
survive. Whole-value LWW would discard a valid concurrent edit from the losing set.

### Why LWW per target?

Follow intent for one target is an atomic register. The later complete record should win,
including its active flag and validated-key cache. Edits to different targets should not
compete.

### Why retain inactive records?

`MapLattice` is additive for unique keys. The inactive record is necessary evidence that
an old active record has been superseded. It is bounded to one current record per target,
not an unbounded action log.

### Why DIDs rather than account keys?

The social identity can remain stable across authorised key rotation. `did:key` provides
intentional immutable-key identity; `did:web` and `did:convex` provide rotation.

## Correctness Invariants

- Every owner and follow target is a canonical base DID.
- Every admitted owner slot has a valid signature from a key authorised for that DID.
- Node identity, social DID and social signing key are independent.
- `:following` is LWP and delegates to a fixed `KeyedLattice` structure.
- Its `:follows` child is a DID-keyed `MapLattice` with LWW records.
- Disjoint target edits from different authorised devices survive merge.
- The later complete record wins when two devices edit the same target.
- Unfollow retains an inactive record; deleting the map entry is not a durable remove.
- A cached-key refresh preserves the current `:active` value.
- All write timestamps come from `LatticeContext`.
- Exact timestamp ties follow CAD024 own/current preference.
- Foreign following values are validated at or above LWP, including first adoption.
- A synthesised structural merge is published only with an authorised owner signature.
- Desired-set expansion is direct, bounded and based only on configured local owners.
- An owner outside the desired set cannot enter through an unsolicited social value.
- Pull completion, not sleep or ping, is the acquisition completion signal.
- Query visibility, inbound admission and physical retention are tested separately.

## Test Plan

Use in-process nodes with separate stores and `NodeConfig.port(0)`.

1. **DID validation and auth:** canonical `did:key`, `did:convex` and `did:web`
   owners/targets accept an authorised signer and reject malformed DIDs or other keys.
   Use pinned CVM state and deterministic `did:web` fixtures.
2. **Identity separation:** node keys differ from social owner/signing keys.
3. **Disjoint merge:** two Alice devices follow different DIDs; both records survive in
   either merge order.
4. **Same-target LWW:** concurrent follow/unfollow records select the higher record
   timestamp in either merge order.
5. **Outer LWP:** the higher following-section stamp is preferred while unique older
   target records survive structural merge.
6. **Missing values:** absent `:following`, absent `:follows` and absent target entries
   have identity/union semantics; malformed first adoption is rejected safely.
7. **Section independence:** concurrent feed, profile and following edits all survive
   according to their child lattices.
8. **Tombstone:** late delivery of an older active record cannot undo an unfollow.
9. **Cache update:** a newly validated target key updates the record without changing
   its active state; stale or failed resolution is rejected.
10. **Signed synthesis:** an authorised Alice context signs a structural merge; a relay
    without Alice authority never emits an unsigned or wrongly signed synthesis.
11. **Rotation:** `did:web` and `did:convex` retain their owner path across valid key
    changes; lost `did:key` creates a new social user.
12. **Selective pull:** following Bob but not Carol materialises Bob's slot only;
    unfollow stops refresh and shared demand keeps Bob while still required elsewhere.
13. **Retention:** cache eviction changes materialisation without asserting deletion of
    the remote owner's data.

Tests wait on pull, publication or lifecycle futures rather than sleeping.

## Delivery Stages

### Stage 0: Lattice and identity

- replace account-key owner/target identifiers with canonical DIDs;
- add the stamped `:following` LWP wrapper around its per-target LWW `:follows` map;
- validate foreign following values at or above LWP, including first adoption;
- move timestamps to `LatticeContext`;
- add last-validated target key/version fields to follow records;
- install fail-closed standard DID authorisation;
- support authorised structural merge and re-signing for local owner contexts;
- migrate or deliberately retire the account-key schema.

### Stage 1: Selective acquisition

- add `SocialReplicationController` with explicit peers;
- derive desired owners from local active follow records;
- pull only desired owner paths;
- enforce path-aware inbound value admission;
- build timelines from each local user's active records;
- document logical-only retention.

### Stage 2: Bounded materialisation

- separate authored state from the remote replica cache;
- evict owners no longer desired after a grace period;
- expose desired/materialised/retained metrics;
- verify Etch reclamation from retained roots.

## Open Decisions

- exact `:following` and follow-record cell encoding;
- context clock policy for cross-device equal-timestamp avoidance;
- which target-key cache metadata is replicated versus operator-local;
- whether profile fields should adopt the same LWP/map/LWW composition;
- composite post-key encoding for concurrent devices;
- cache retention limits and eviction grace period.
