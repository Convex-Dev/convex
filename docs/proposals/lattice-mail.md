# Lattice mail: asynchronous DID-addressed messaging

- Status: Proposed; prototype in `convex.lattice.mail` (convex-core) with a two-node
  delivery test in `convex.node.app.MailDeliveryTest` (convex-peer). Node policy,
  including the admission window and receipt retention, and the `P2PNode`
  integration are not yet built.
- Last updated: 2026-10-17
- Governing CADs: [CAD024](https://docs.convex.world/docs/cad/data_lattice) (merge
  rules), [CAD038](https://docs.convex.world/docs/cad/lattice_auth) (owner
  authorisation), [CAD043](https://docs.convex.world/docs/cad/did) (DIDs),
  [CAD045](https://docs.convex.world/docs/cad/lattice_apps) (applications),
  [CAD052](https://docs.convex.world/docs/cad/encryption) (encryption). The
  envelope and receipt formats and the merge rule below need a new CAD before they
  are normative; this document is the implementation proposal.

## Summary

A `:mail` region gives every DID an inbox on the lattice. Anyone may deposit a
signed, optionally encrypted envelope into a recipient's inbox. Nodes that have
opted in to a mailbox store it and merge deposits from every source, so messages
accumulate while the recipient is offline. The recipient signs a receipt for each
message it has taken, and the receipt replaces the message wherever it merges.

The whole data model is one map and one register:

```
:mail → Map<DID, Index<t‖hash, Entry>>

Entry is a two-state register per message:
  Deposited   SignedData<Envelope>     signed by any key
  Received    SignedData<Receipt>      signed by a key authorised for the mailbox DID
  Received replaces Deposited. The receipt is the tombstone; the message body is gone.
```

Three properties follow from keeping it this small:

1. **Every merge rule is a union or a two-element chain.** Nothing is truncated,
   selected across fields or synthesised, so relays converge without holding any
   recipient key and the lattice laws hold without argument.
2. **Delivery is order-agnostic.** Deposits are a set, receipts are per message,
   and reliability is sender-driven at-least-once delivery, idempotent by hash.
   Nothing depends on messages from one sender arriving in order.
3. **Bounds and expiry are retention policy, not lattice rules.** A peer drops
   whatever it does not want to retain and union recovers it from any holder.
   Receipts age out under the same window that refuses old envelopes, so an
   expired receipt can never let its message back in.

## Context

Convex already has three pieces of this picture, none of which delivers a message
to a DID that is offline:

- **Point-of-presence messages** (`convex-p2p/docs/POINTS_OF_PRESENCE.md`) route a
  signed, optionally ECIES-encrypted value to a *node key* with a five-minute
  lifetime. They are fire-and-forget, never persisted and never addressed to a
  user identity.
- **ECIES** (`convex.core.crypto.ECIES`, CAD052) encrypts a Blob to the holder of
  an Ed25519 account key using HPKE Base mode. It is deliberately
  non-authenticating and carries no recipient hint or associated data.
- **The social region** (`convex-social/docs/SOCIAL_DESIGN.md`) shows the pattern
  for DID-keyed slots, DID signer authorisation through `DIDKeyAuthorizer`, and a
  one-hop replication policy applied at ingress and publication.

The `:queue` region (CAD040) is a single-leader log per partition. An inbox is the
opposite shape: many independent writers and one reader, with no leader to assign
offsets.

## Goals

- Deliver a message to any canonical base DID while the recipient is offline, by
  letting the message accumulate on nodes that have chosen to hold that mailbox.
- Keep bodies confidential from every storing node, using the existing ECIES
  wrapper unchanged.
- Authenticate the sender to the recipient, with an option to hide the sender
  from storing nodes.
- Give the recipient a signed, lattice-native receipt that removes the message
  everywhere and that the sender can read as delivery confirmation.
- Let any node store any mailbox on an opt-in basis, with a default policy drawn
  from P2P connectivity and social follows.
- Keep the data model minimal and every merge rule a pure function of the slot.

## Non-goals

- Ordered delivery. The lattice cannot promise arrival order and the region does
  not try; a protocol that needs it carries its own sequence inside the payload.
- Low-latency notification. Point messages remain the fast path.
- Group or broadcast messaging. One envelope has one recipient.
- Spam economics. The design bounds abuse by policy and leaves hooks for stamps or
  x402 payment proofs; it does not price delivery.
- Traffic analysis resistance beyond hiding the body and, optionally, the sender.

## Design invariants

1. **Merge is a pure function of the slot.** No clock, no external region, no
   node configuration and no capacity bound appears in a merge rule.
2. **Merge never synthesises a signature.** Every entry is stored with the
   signature it arrived with.
3. **Nothing is dropped on the strength of another entry's presence.** A message
   leaves the lattice only when its own receipt arrives. This is what makes the
   laws hold without a proof.
4. **Node policy may be stricter than the lattice, never looser.**
5. **Nothing in `:mail` grants authority elsewhere.** A deposited envelope is data.
   It updates no feed, follow list or identity record.

## Data model

### Region shape

```
:mail → MailLattice                                  Map<DID, mailbox>
  <recipient-did> → MailboxLattice (bound to the DID)  Index<t‖hash, Entry>
     <t‖hash> → SignedData<Envelope>                   Deposited
              | SignedData<Receipt>                    Received
```

Only canonical base DIDs (`DID.isCanonicalBase`) are mailbox keys, as for social
owners. `MailLattice.path(did)` returns a `MailboxLattice` bound to that DID, so
the entry rule knows which recipient it serves: it rejects an envelope addressed
elsewhere and verifies that a receipt's signer is authorised for that DID.

Signer authorisation follows the bound-verifier pattern in
`LATTICE_APPLICATIONS.md`: `MailLattice.create(DIDKeyAuthorizer)` binds the rule
to the lattice instance. `Mail.LATTICE` is the `did:key`-only default
(`DIDKeyAuthorizer.CONVEX`), which fails closed for indirect DIDs. A node with a
richer authoriser composes `MailLattice.create(authorizer)` into its root. Nodes
with different authorisers interoperate on every DID both can resolve.

### Entry key

```
key = issued-at as 8 bytes big-endian ‖ hash of the SignedData<Envelope>
```

The key is computable from either state: an envelope from its own `issued-at`
and hash, a receipt from the two fields it carries. A receipt therefore lands on
exactly the entry it confirms. The time prefix gives ordered reading and lets a
node expire old entries with one `Index.slice`. Two envelopes under one key are
identical by construction.

### Envelope

A deposit is a `SignedData` over a CAD3 vector:

```
["convex-mail-v1" to from issued-at encrypted body]
```

| Field | Type | Meaning |
|---|---|---|
| context | `AString` | Fixed `"convex-mail-v1"`; separates mail from other signed vectors and carries the format version |
| `to` | `AString` | Recipient canonical base DID; must equal the map key |
| `from` | `AString` or `nil` | Claimed sender DID, or `nil` for an unattributed envelope |
| `issued-at` | `CVMLong` | Sender timestamp, epoch milliseconds |
| `encrypted` | `CVMBool` | Whether `body` is an ECIES envelope |
| `body` | `Blob` or any cell | ECIES ciphertext of the CAD3 multi-cell encoding of the payload, or a plain payload |

The `SignedData` signer `K` is the envelope author. `to` sits inside the
signature so an envelope cannot be moved into another mailbox. `from` is optional:
in the clear it lets storing nodes filter by sender and lets a client show the
sender before decrypting; a sender who wants neither leaves it `nil` and puts its
identity inside the ciphertext.

**Attribution.** When `from` is present, the recipient checks that `K` is
authorised for `from` through its `DIDKeyAuthorizer`; storing nodes verify only
the signature. When `from` is `nil`, `K` may be a fresh ephemeral key. A sender
who wants to prove identity to the recipient without revealing it to storing
nodes signs the payload with its real key, encrypts that inner `SignedData`, and
signs the outer envelope with an ephemeral key.

**Binding the header into the plaintext.** CAD052 fixes the HPKE `info` and `aad`
inputs as empty, so the ciphertext alone is not bound to the envelope around it.
The plaintext therefore always carries the header fields it must match:

```
{:to <recipient-did> :from <sender-did or nil> :issued-at <ms>
 :id <blob or nil> :type <keyword> :content <any> ...}
```

The recipient rejects a message whose inner `:to`, `:from` or `:issued-at`
disagree with the outer header. `:id` is an application-level message identity
that survives a resend (a resend has a new `issued-at`, hence a new hash), so the
recipient can deduplicate by `:id`. Large content belongs in the `:data` region
with a hash reference in `:content`.

**Encryption key.** The sender encrypts to the recipient DID's key: for `did:key`
the key the DID encodes, as `ECIES.encrypt` already supports; for an indirect DID
whatever its method resolves to, through a small `DIDKeyResolver` beside the
existing authoriser. A DID that resolves to no key cannot be encrypted to, and
`send` fails unless the caller explicitly allows a plain body. CAD052 says
protocols that can use a separate long-term encryption key should do so; that
belongs in DID documents (`keyAgreement`, CAD043) rather than in the mailbox, and
is left for later.

### Receipt

A receipt is a `SignedData` over a CAD3 vector, signed by a key authorised for
the mailbox DID:

```
["convex-mail-receipt-v1" did hash issued-at]
```

`hash` and `issued-at` name the envelope and reproduce its key. `did` sits
inside the signature so a receipt signed for one mailbox cannot be placed in
another by someone who has seen it, even when one key is authorised for both
DIDs, which is the ordinary `did:key` plus `did:web` alias case.

A receipt is a complete entry on its own. If it arrives before, or without, the
envelope it names, it is a stable tombstone that absorbs the envelope whenever it
turns up.

## Merge rule

`MailboxLattice` is an index lattice whose per-key rule is the register. Writing
`own` and `other` for the two values under one key:

```
valid(e)  envelope: signature valid; context known; to == mailbox DID;
          key == t‖hash of the signed value; encrypted body is a Blob of at least ECIES.OVERHEAD
valid(r)  receipt:  signature valid; signer authorised for did under the bound verifier;
          did == mailbox DID; key == t‖hash from its own fields

join      Received  if either side is a valid receipt
          Deposited otherwise, if either side is a valid envelope
          absent    otherwise
```

Two receipts under one key, from two of the recipient's devices, resolve by the
canonical symmetric tiebreak that CAD045 allows: the lower hash of the signed
value wins. Nothing is lost by picking either, because coverage is the state and
not the signer, and picking deterministically avoids a stable CAD024 tie between
peers. Two envelopes under one key are the same envelope.

At the map level `MailLattice` unions mailboxes, admitting only canonical base DID
keys, and merges each mailbox with its DID-bound `MailboxLattice`.
`Index.mergeDifferences` visits only keys that differ, and `SignedData` caches
signature verification, so a merge costs O(delta) as in CAD038.

The per-key rule needs the key, so `MailboxLattice` is a union lattice
(`AUnionLattice`, the base the map, index and owner lattices share) whose
`path(key)` returns a register bound to that key and to the mailbox DID, as
`signingFor` returns an owner-bound `SignedLattice`; the base merges each entry
with that child, rather than with the one shared child a plain `IndexLattice`
has. Receipts are authored through
an explicit owner-aware signer `(did, receipt) -> SignedData`. The mailbox
checks the returned payload, signature and DID authority using the same bound
rule as incoming receipt merge. A signer may handle multiple DIDs.

**Why it is a lattice.** Per key the values form a chain, absent below Deposited
below Received, with Received made canonical by the tiebreak; join is the
maximum. The index and the map are unions with that per-key join. Chains and
unions compose, and there is nothing else.

## Cleanup and retention

**Lattice-native cleanup.** A receipt replaces the message wherever it merges.
The message body leaves every peer that learns of the receipt, leaving a
tombstone of about 190 bytes.

**Retention policy.** Receipts age out by policy, not by a lattice rule. A node
refuses envelopes whose `issued-at` is older than its admission window, and drops
receipts no earlier than that same window plus a clock-skew allowance. An expired
receipt can therefore never let its message back in anywhere that would also
have refused the message. Beyond that, a peer drops whatever it does not want to
retain, and union recovers it from any holder that kept it. Receipts are small
and should outlive envelopes of the same age.

**Not included: a recipient-signed horizon.** A `[did H]` entry under max select
would give the recipient lattice-native control of expiry and a bulk abandon of
old unconfirmed mail. It is sound, and the one addition worth making if
recipient-controlled expiry turns out to matter, but the requirements do not need
it and retention policy covers the same ground.

## Node policy

Node policy decides which mailboxes a node holds, which deposits it admits and
what it retains. It is applied by a `MailReplicationPolicy` in `convex-p2p`,
installed exactly as `SocialReplicationPolicy` is: as the propagator's ingress
filter, its publication filter and the set of paths pulled on connect.

### Desired mailboxes

```
desired = L ∪ P ∪ F(L) ∪ C ∪ O
```

| Set | Members | Source |
|---|---|---|
| `L` | DIDs registered through `P2PNode.mail(did, signer)` | local users |
| `P` | DIDs pinned by the operator | `pinMailbox` |
| `F(L)` | direct active follows of local users | the recipient's `:social` slot, via `SocialHelpers.computeFollowSet`; one hop, as for social |
| `C` | `did:key` of each node key currently authenticated as a peer | the propagator's verified connections; opt-in through `storeMailForPeers(true)` |
| `O` | recipients of local users' unreceived outgoing envelopes | deposited entries in held mailboxes, selected by local signing keys |

`O` is what makes the sender's node a courier: the mailbox is held until the
receipt arrives or the envelope ages out of the window, then leaves the desired
set. The desired set is bounded (`maxDesiredMailboxes`) and recomputed when any
source changes. This is a derived view of committed mailbox entries, not a second
store of envelopes. Match local signing keys, not an unverified `from` claim.

### Admission

The mail application takes `[:mail <did> ...]` values only for desired DIDs,
selecting the desired mailboxes from a root or region value, and applies the
checks the lattice cannot before calling its merge:

- complete value within the inbound size bound; each envelope within the
  envelope bound;
- `issued-at` within `[now - window, now + clock-lead]`;
- per-connection and per-node deposit rate limits, with rejections counted by the
  existing per-connection circuit-breaker;
- optionally, a sender filter for a mailbox: admit only envelopes whose signer
  is a key cached in the recipient's follow records (`:account-key` in
  `FOLLOWING.md`), or whose `from` is authorised for a DID the recipient follows.
  A node that does not hold the recipient's social slot cannot evaluate this and
  falls back to admitting every valid envelope or none, as the operator chooses.

Admission applies to values arriving from the network. A local user's own deposit
into a courier copy is the operator's choice and is not filtered.

### Retention

A node keeps what its operator wants. The defaults drop entries older than the
window (receipts a skew allowance later than envelopes), and drop whole slots that
leave the desired set, as the social policy does for unfollowed owners. An
operator may also cap entries per mailbox; a cap is a retention choice with no
lattice meaning, and a deposit dropped by one node is recoverable from any other
that kept it, including the sender's copy of that mailbox. Removing the last
retained copy deliberately discards that deposit; sync does not recreate it from
a hidden buffer. Logical retention is the projected root; Etch reclamation follows
normal store policy.

### Replication

On connect, a node pulls `[:mail <did>]` for each desired DID, after the `:p2p`,
`:id` and desired social pulls. On a local deposit, the sender's node pushes the
path-scoped `[:mail <to>]` value to any connected peer that desires the mailbox,
and otherwise relies on gossip. Nothing here needs new transport: path-scoped
`LATTICE_VALUE` messages and pulls already exist.

## Delivery flow

```
 Alice's device           Alice's node (courier)        Bob's home node          Bob's device
 ─────────────            ──────────────────────        ───────────────          ────────────
 compose, encrypt to
 Bob's key, sign   ──►  deposit [:mail bob]
                         Bob ∈ desired (O)       ──►   admit, merge             (offline)
                                                       ...
                                                       pull [:mail bob] ◄──  connect
                                                                           decrypt, verify
                                                                           inner :to/:from
                                                                           sign receipt   ──► merge; body gone
                         merge receipt ◄── gossip ◄──  publish
                         receipt replaces body
                         Bob ∉ desired
 receipt seen: delivered
```

Reading a mailbox needs no special API: the recipient's component pulls the slot
from any node that holds it, iterates Deposited entries in time order, opens each
with its decryptors, checks the inner binding, authorises `from`, and writes a
Received entry for each message it takes. Local read state stops it reprocessing
an envelope it has already opened but not yet published a receipt for.

A receipt is on behalf of the DID, not a device. Once one of the recipient's
devices receipts a message, the body leaves the lattice for every other device
too. Keeping taken messages available across the recipient's devices is the
application's concern, for example the owner's own DLFS drive; the mailbox is a
delivery queue, not a store.

**Sender's view.** A receipt under the envelope's key means received. No receipt
seen before the envelope ages out of the window means not delivered: the sender
resends with a fresh `issued-at` and the same `:id`. A resend of a message whose
receipt was delivered but has since expired is a duplicate the recipient drops by
`:id`. The sender holds the deposited entry in its local copy of the recipient's
mailbox until a receipt replaces it or retention removes it. Ordinary replication
propagates that state; sync does not redeposit envelopes. Repeated delivery is
idempotent by hash on the lattice and by `:id` at the application.

`send` stores the envelope once, at `[:mail recipient entry-key]`. It uses the
mailbox's atomic cursor update and participates in any enclosing transaction or
fork. A successful transaction commits all its sends; an aborted attempt leaves
none, and retries reuse envelopes prepared before the callback. No extra sender
state needs committing, copying into a fork or restoring after restart. Pending
envelopes and delivery receipts are read from the mailbox itself. Local commit,
application publication, host persistence and remote receipt are distinct steps.

## Key API surface

All types below are proposals. Component shapes follow `Social` and
`SocialUser`; signatures are the intended public surface, with one-line
semantics.

### `convex.lattice.mail` (convex-core)

```java
public final class MailLattice extends AUnionLattice<ACell, Index<ABlob, SignedData<ACell>>, AHashMap<...>>
    static MailLattice create(DIDKeyAuthorizer authorizer)   // binds the receipt signer rule
    MailboxLattice forMailbox(AString did)                   // DID-bound mailbox lattice; also returned by path(did)

public final class MailboxLattice extends AUnionLattice<ABlob, SignedData<ACell>, Index<ABlob, SignedData<ACell>>>
    // union of entries, each merged with the register bound to its key; zero() = Index.EMPTY
    MailEntryLattice entry(ACell key)                        // key-bound register; also returned by path(key)

public final class MailEntryLattice extends AValueLattice<SignedData<ACell>>
    // the per-key register as specified above, bound to the mailbox DID and the entry key
    static ABlob key(long issuedAt, Hash envelopeHash)       // t‖hash

public final class Mail extends ALatticeComponent<AHashMap<ACell, Index<ABlob, SignedData<ACell>>>>
        implements TransactionalComponent<..., Mail>
    static Keyword KEY_MAIL                                  // :mail
    static MailLattice LATTICE                               // did:key-only default
    static Mail create(BiFunction<ACell,ACell,SignedData<ACell>> signer) // standalone root
    static Mail connect(ALatticeApplication<Index<Keyword,ACell>> app, MailLattice lattice,
        BiFunction<ACell,ACell,SignedData<ACell>> signer)
    Mailbox mailbox(AString did)                             // view of any mailbox
    Hash send(MailEnvelope envelope)                         // one atomic deposit in the recipient mailbox
    Mail fork(); void sync();

public final class Mailbox extends ALatticeComponent<Index<ABlob, SignedData<ACell>>>
    AString did()
    List<MailEnvelope> deposited()                           // Deposited entries in time order, undecrypted
    boolean isReceived(Hash envelopeHash, long issuedAt)     // a Received entry exists under the key
    List<MailMessage> open(ECIES.Decryptor... decryptors)    // decrypt, bind, authorise from; skips what it cannot open
    // owner operation: the supplied signer must be authorised for did()
    void receive(Collection<MailEnvelope> envelopes)         // writes one Received entry per envelope

public final class MailEnvelope                              // parse(ACell) returns null when malformed
    Hash hash(); ABlob key(); AccountKey signer(); AString to(); AString from()
    long issuedAt(); boolean encrypted(); ACell body(); SignedData<AVector<ACell>> signed()

public final class MailReceipt                               // parse(ACell) returns null when malformed
    AString did(); Hash hash(); long issuedAt(); ABlob key(); SignedData<AVector<ACell>> signed()

public record MailMessage(Hash hash, AString to, AString from, AccountKey signer,
    boolean fromAuthorised, long issuedAt, ACell payload, boolean encrypted)
```

### `convex.auth.did` (convex-core)

```java
public interface DIDKeyResolver
    AccountKey resolve(AString did)              // the key to encrypt to, or null; did:key inline, others pluggable
    static DIDKeyResolver forState(State state)  // did:key plus numeric did:convex, beside DIDKeyAuthorizer.forState
```

### `convex.p2p` (convex-p2p)

```java
P2PNode
    Mail mail(AString did, AKeyPair signer)      // registers a local mailbox owner
    Mail mail(AKeyPair signer)                   // did:key convenience
    void pinMailbox(AString did)                 // retain a mailbox independently of local users
    P2PNode storeMailForPeers(boolean enabled)   // include did:key of authenticated peers in the desired set

P2PLattice
    NODE_ROOT  gains  :mail → Mail.LATTICE       // a node with an authoriser composes MailLattice.create(authorizer)

MailReplicationPolicy (package-private)          // desired set, ingress and publication filters, mailbox courier, retention
```

### Catalogue entry

`convex-core/docs/LATTICE_REGIONS.md` gains the row:

| Region | Lattice | Path | Notes |
|---|---|---|---|
| `:mail` | `MailLattice` (`Map<DID, Index<t‖hash, Entry>>`) | `[:mail <did> <t‖hash>]` | Per-DID inbox. Each entry is a two-state register: a signed envelope deposited by anyone, replaced by the recipient's signed receipt. Union merge; retention by policy. |

## Policy defaults

The lattice has one validity bound: a body larger than
`MailEnvelope.MAX_BODY_LENGTH` (64 KiB) is dropped by the merge before anything
else is examined, so a flood of large envelopes costs no verification. Every
other limit is a node default an operator may change.

| Limit | Value |
|---|---:|
| Body (lattice validity) | 64 KiB |
| Admission window (envelope age) | 7 days |
| Receipt retention beyond the window | 1 hour (clock-skew allowance) |
| Clock lead | 5 minutes |
| Deposits per connection | 16 per second |
| Desired mailboxes per node | 1,024 |
| Entries retained per mailbox | unlimited |

The window bounds the age of an envelope, not the delivery delay: a message older
than the window is resent by its sender under a fresh `issued-at`. A shorter
window means receipts live less long; a longer one tolerates longer outages
without a resend.

## Threat model

| Threat | Defence |
|---|---|
| Deposit forged as another sender | `from` must be authorised for `K` at the recipient; storing nodes verify the signature |
| Forged receipt | signature plus DID authorisation under the bound verifier |
| Envelope moved to another mailbox | `to` is inside the signature and must equal the map key |
| Receipt moved to another mailbox | `did` is inside the signature and must equal the map key |
| Ciphertext lifted into a new envelope | inner `:to`, `:from`, `:issued-at` must match the outer header (CAD052 recommends binding inside the plaintext) |
| Replay | content-addressed: a redeposit is a no-op; a receipt outlives the window in which its envelope could be admitted |
| Flooding a mailbox | admission window, envelope size, rate limits and the optional sender filter; residual: a Sybil flood fills whatever a node chooses to retain for an open mailbox, so public mailboxes should use the sender filter or a retention cap, and stamps or x402 proofs are the planned hook for paid stranger access |
| Inferring who writes to whom | storing nodes see `to`, the optional `from`, `K`, `issued-at` and size; unattributed envelopes hide the sender; bodies are opaque; padding is an application choice (CAD052) |
| Key reuse across algorithms | the Ed25519 to X25519 conversion CAD052 specifies; a dedicated encryption key is a DID document concern |
| Lost recipient key | a `did:key` recipient cannot rotate; indirect DIDs rotate through their method; messages encrypted to a lost key are unreadable but can still be receipted |

The outer signature makes an attributed message non-repudiable to anyone holding
the envelope, and a receipt is non-repudiable evidence that a key authorised for
the recipient saw the envelope hash. Senders who need deniability use an
unattributed envelope with identity, if any, inside the ciphertext.

## CAD impact

- **New CAD** (lattice mail): envelope and receipt formats, the entry key, the
  register rule, the retention rule that keeps receipts at least as long as the
  admission window, and the recipient's binding check. Node policy belongs in the
  same CAD as recommended behaviour, as the social policy is described today.
- **CAD052**: no change. The plaintext binding follows its existing guidance.
- **CAD043**: a later note on `keyAgreement` keys for mail, and on
  `DIDKeyResolver` beside `DIDKeyAuthorizer`.
- **CAD045 / LATTICE_REGIONS**: catalogue row above.
- **CAD036**: none; the node replicates and merges nothing (design log entry 16).

## Testing

Lattice laws, in `convex-core`:

- `LatticeTest.doLatticeTest(MailboxLattice.forMailbox(did), v1, v2)` over
  generated indexes mixing envelopes and receipts from several keys, plus a
  seeded random-order merge check over three or more slots.
- Received dominates Deposited in both operand orders; a receipt that arrives
  first is a stable tombstone that absorbs the envelope when it arrives; two
  receipts for one key converge to the lower-hash one in every merge order.
- A deposit and a receipt for different messages leave each other alone.

Adversarial, at the raw data level:

- receipt signed by an unauthorised key; receipt bound to another DID placed in
  this mailbox; envelope with `to` of another DID; entry whose key is not its
  value's `t‖hash`; oversize, back-dated and future-dated envelopes at ingress;
  ciphertext re-wrapped under a new `from` rejected by the recipient's binding
  check.

Retention, in `convex-p2p`:

- a node that has expired a receipt refuses the envelope from a peer that still
  holds it; receipts are retained at least a skew allowance beyond envelopes of
  the same age.

Components:

- standalone `Mail.create` with two key pairs: compose, deposit, open, receive,
  observe `isReceived`; `did:web` recipient through an `alsoKnownAs` authoriser;
  unattributed envelope with an inner signature.

Network, in `convex-integration` alongside the social and DLFS stories:

- Alice's node, Bob's home node and Bob's device, with Bob offline when Alice
  sends. Alice's node couriers the mailbox; Bob's node connects and pulls it;
  Bob's device opens and receipts; the receipt reaches Alice's node, which drops
  the body at the same entry. Each step waits on a connect, pull, publication
  or upgrade future. No sleeps; port `0`.
- A public node with the sender filter on: an envelope from a key cached in the
  recipient's follow records is admitted, a stranger's is rejected at ingress,
  both without any recipient key present.

## Anticipated code changes

- `convex-core`: package `convex.lattice.mail` (lattices, components, envelope and
  receipt helpers), `DIDKeyResolver` in `convex.auth.did`, tests in
  `convex.lattice.mail`, catalogue row in `LATTICE_REGIONS.md`, a paragraph in
  `LATTICE_APPLICATIONS.md` under *Deletes* describing the receipt-as-tombstone
  register as the pattern for multi-writer inboxes.
- `convex-p2p`: `MailReplicationPolicy`, `P2PNode.mail`, `pinMailbox`,
  `storeMailForPeers`, the extra pulls on connect, `:mail` in `NODE_ROOT`, a
  `docs/MAIL.md` cross-reference from the README.
- `convex-integration`: the network stories above.
- `design` repository: the new CAD and the CAD045 catalogue mention.

## Alternatives considered

The model above is the end of a sequence of designs that each tried to do more
inside the lattice. Each was rejected for a concrete reason, recorded here because
the sequence is the argument for the minimal model.

### Flat inbox with batched receipts and compaction

The first design kept a content-addressed inbox and batched receipts holding sets
of acknowledged hashes, compacted by a keeper rule, with capacity bounded at
admission. Coverage needed the union of every acknowledgement across every
receipt on each merge, and the compaction rule needed a fixed total order to be
sound. It also showed that no capacity bound can live inside the merge: with a
bound of one entry kept oldest-first, `{x:10, y:20}` normalises to `{x}`, and
acknowledging `x` then yields the empty inbox one way and `{y}` the other. A cap
on receipts fails the same way by dropping a keeper. Per-message receipts
survive from this design; the batching and the bounds do not.

### Per-sender rings with slot registers

To bound the inbox structurally, each sender key got a fixed ring of slots, each
slot a register under LWW by `issued-at`, with one acknowledgement vector per
sender. Sound, because the register and the threshold share one axis, and
compact. Rejected because bounds are not wanted: a peer may drop anything it
does not want to retain, and union recovers it. A sender overwriting its own
unread message is a consequence nobody asked for.

### Per-sender logs with watermarks

Without bounds, a per-sender log with a recipient-signed cumulative watermark
gives O(1) confirmation state per sender. A confirmation names a subset of a set
with no shared order, and the only way to compress that is to exploit each
writer's own order, which is why compact confirmation is per-writer by
necessity. The price is a protocol: a `prev` chain so the recipient can detect
gaps, unique `issued-at` per recipient, and retransmission of the oldest
unconfirmed envelope by the sender. For ephemeral keys it collapses to
per-message anyway. It imports an ordering requirement the application does not
have, and the receipt-as-tombstone register gets the same cleanup with none of
the protocol.

### A mailbox-wide horizon as the only confirmation

One recipient-signed number would be the simplest receipt of all. It either
prunes late arrivals it has never seen, or it must lag by the whole admission
window before it is safe to advance. It remains a sound optional addition for
recipient-controlled expiry, not a replacement for per-message receipts.

### Confirmations expiring by their own signing time

If a receipt expired when the recipient's signing time fell below a threshold,
a recipient clock behind the sender's would give different results in different
merge orders: `A = {e}`, `B = {receipt signed at c < t_e}`, `C = {threshold with
c <= H < t_e}` yields the empty inbox one way and `{e}` the other. A receipt must
carry the message's own `issued-at`, which the entry key already does, so that
it ages out exactly when the message would be refused anyway.

### An owner-signed inbox snapshot

The recipient could re-sign the whole inbox with the taken messages removed, as
the social region re-signs an owner's value. Selection by LWW is not monotone in
coverage, so two devices lose each other's confirmations until the owner
re-signs a union. The individual receipt costs the same bytes per message with
no healing and no device coordination.

### Reuse the `:queue` region

A queue partition has one leader assigning offsets. An inbox has many writers and
one reader; it needs union merge and a reader-signed removal, not a
single-leader log.

### Point-of-presence messages only

No persistence and a five-minute lifetime. They remain the right fast path and
can later wake a recipient's node when mail arrives.

### Context owner verifier, as the social region does today

Works, and keeps a single static lattice instance, but `LATTICE_APPLICATIONS.md`
directs new regions to bind the authorisation rule to the lattice. The design
follows that guidance and keeps a `did:key`-only default for callers without an
authoriser.

### A separate `convex-mail` module

Every dependency (ECIES, DID, `SignedData`, the lattice core) already lives in
`convex-core`, which also hosts `:kv`, `:queue` and `:fs`. Placing the region in
core lets the CLI, REST API and DLFS use it without `convex-p2p`. The node policy
still lives in `convex-p2p`, as the social policy does. A module split remains
possible later if mail grows a product surface of its own.

## Open implementation choices

- Whether to add the recipient-signed horizon from the outset, for bulk abandon
  of old unconfirmed mail, or wait until a need appears.
- Whether a sender may put a `:prev` reference inside the plaintext as a gap hint
  the recipient's client can display, without the lattice depending on it.
- How `DIDKeyResolver` obtains keys for `did:web` recipients: through the same
  `alsoKnownAs` aliases the authoriser uses, or a `keyAgreement` entry once DID
  documents carry one.
