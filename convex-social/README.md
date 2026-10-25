# Convex Social

[![Maven Central](https://img.shields.io/maven-central/v/world.convex/convex-social.svg?label=Maven%20Central)](https://search.maven.org/search?q=world.convex)
[![javadoc](https://javadoc.io/badge2/world.convex/convex-social/javadoc.svg)](https://javadoc.io/doc/world.convex/convex-social)

A lattice-based, peer-to-peer social network built on Convex. The module currently
provides DID-owned feed and follow state plus timeline construction primitives.
`P2PNode` wires direct-follow replication, DID signer validation and selective
retention around this application lattice.

> **Identity model:** canonical DIDs identify owners, follows, replies and timelines;
> signing keys are authorised separately through standard Convex auth. The social value
> remains structurally mergeable beneath its owner signature.
> Follows use a stamped LWP collection containing a DID-keyed map of LWW records, so
> separate devices can update different targets without losing either edit. Unfollow is
> represented by the winning inactive record for its target.
> `did:key`, pinned `did:convex` and authenticated `did:web`/`alsoKnownAs` bindings are
> supported through `DIDKeyAuthorizer`; see
> [Social Lattice Design](docs/SOCIAL_DESIGN.md).
> A `did:key` user is stable and non-recoverable: losing its private key means creating
> a new social user rather than replacing the key behind the existing DID.

## Overview

The social lattice composes standard Convex lattice primitives into a two-level
structure:

```
SocialLattice (per-user, signed by an authorised DID key):
  :feed      → IndexLattice<Blob, ACell>    last-writer-wins per entry
  :profile   → LWWLattice                   display name, bio, avatar, etc.
  :following → LWPLattice
    :follows → MapLattice<DID, LWW record>  active intent and timestamp
```

Each user's complete data is wrapped in `SignedData` via an `OwnerLattice`. DID ownership
retains the concrete signer in `SignedData` while resolving its authority independently
of the durable social identifier. Authorised owner devices can merge and re-sign structural
changes; a relay cannot forge a synthesised owner value. The base layer is intentionally
minimal and extensible — easy to layer applications and UI on top.

## Features

Data getters return `null` for absence and throw on incompatible stored values.
Empty collections are returned only when present in storage or produced by an
explicit aggregation. Counts and predicates use zero/false for missing data.
See [Reading domain state](docs/SOCIAL_DESIGN.md#reading-domain-state).

- Owner-signed feeds — only a key authorised for the owner can post
- Signed follow/unfollow records with per-target LWW merge
- Timeline helper for merging selected feeds by timestamp
- Owner-scoped pull primitives through the P2P node substrate
- Lattice merge semantics for independently updated replicas
- Cursor-based Java API with `fork()` / `sync()` for batched operations

## Installation

### Maven

```xml
<dependency>
    <groupId>world.convex</groupId>
    <artifactId>convex-social</artifactId>
    <version>0.8.18</version>
</dependency>
```

### Gradle

```groovy
implementation 'world.convex:convex-social:0.8.18'
```

## Usage

```java
import convex.social.Social;
import convex.social.SocialUser;
import convex.auth.did.DID;
import convex.core.crypto.AKeyPair;
import convex.core.data.AString;

AKeyPair keyPair = AKeyPair.generate();
AString myDid = DID.forKey(keyPair.getAccountKey());

// Standalone instance with its own cursor
Social social = Social.create(keyPair);
social.user(myDid).feed().post("Hello!");

// Fork inside one owner's signing boundary, then publish one signed user value
SocialUser work = social.user(myDid).fork();
work.feed().post("Post 1");
work.feed().post("Post 2");
work.sync();
```

One component can author several DIDs. Supply an owner-aware signer and a clock;
keep key resolution separate from the rule which authorises those keys:

```java
var keys = new ConcurrentHashMap<ACell, AKeyPair>();
keys.put(aliceDid, aliceKeyPair);
keys.put(bobDid, bobKeyPair);

Social social = Social.create(authoriser, (did, value) -> {
    AKeyPair key = keys.get(did);
    return key == null ? null : key.signData(value);
}, Utils::getCurrentTimestampCell);

social.user(aliceDid).feed().post("Alice's post");
social.user(bobDid).feed().post("Bob's post");
```

`authoriser` is a `DIDKeyAuthorizer` using an authenticated authority view.
`DIDKeyAuthorizer.CONVEX` handles self-certifying `did:key` identities; indirect
DIDs require an explicit resolver. The signer may use a wallet, keystore or signing
service instead of a map. Returning null means it cannot author that owner.
Every signature must cover the requested value and pass the same DID rule as
incoming delivery. A signing exception aborts the atomic edit or fork sync.

Forks, user views and transaction views retain these functions, so existing views
can observe key rotation through the supplied resolver. Callbacks must support
concurrent use and retries; refresh external authority outside atomic updates.
A null signer creates a relay, which reuses existing envelopes and keeps its own
value when synthesis would need a new signature. No component inherits signing
authority or timestamps from `LatticeContext`.

For an embedded region, pass the cursor directly to
`Social.connect(cursor, authoriser, signer, clock)`. The no-policy
`Social.connect(cursor)` and `Social.connect(application)` overloads are relay views.

### Node integration

Convex Social is an **application layered on P2P infrastructure**. It supplies one
lattice region, `:social`; discovering peers, advertising node identity and moving values
between nodes are convex-p2p's job, not its own.

There is no separate "social node" to run. `convex-p2p` is a rollup package that bundles
this region along with the P2P infrastructure and the node server, so its `P2PNode` is
the only node server, serving `:social` by default as part of `P2PLattice.NODE_ROOT`:

```java
P2PNode node = P2PNode.create(store, config, keyPair);   // serves :social
```

An operator who does not want social passes the infrastructure-only region set instead,
and still runs a fully capable P2P discovery node:

```java
P2PNode relay = P2PNode.create(store, config, keyPair, P2PLattice.ROOT);
```

Region sets need not match across a network — a node that does not serve `:social`
ignores the region rather than failing on it — so social can be rolled out to part of a
network without a coordinated upgrade.

The bundling is one-directional: convex-p2p depends on convex-social, not the reverse.
This module remains usable on its own, with no P2P node involved, as in the standalone
example above.

For a node that also wants convex-core's application regions (`:data`, `:fs`, `:kv`,
`:queue`), composing onto `Lattice.ROOT` remains equally valid:

```java
KeyedLattice root = Lattice.ROOT.addLattice(Social.KEY_SOCIAL, Social.SOCIAL_LATTICE);
```

A `Social` region is normally connected beneath the node's application component.
This preserves the component hierarchy and delegates persistence to the generic root
host without making the social branch depend on `NodeServer`:

```java
P2PApplication application = node.getApplication();
AKeyPair userKeyPair = AKeyPair.generate();
AString myDid = DID.forKey(userKeyPair.getAccountKey());
Social social = node.social(myDid, userKeyPair);
social.user(myDid).feed().post("Hello");
application.sync();
```

`P2PNode.social` also registers the DID as local, so its own slot and direct active
follows form the default replicated social view. `Social.connect(parent, keyPair)` is
available for lower-level hosting when follow-driven P2P policy is not required.
Registering several DIDs on one P2P application returns the same social component;
its signer selects each registered DID's key. Registering a replacement authorised
key updates existing views without replacing the DID.

## Design

See [docs/SOCIAL_DESIGN.md](docs/SOCIAL_DESIGN.md) for the full lattice architecture and
[docs/FOLLOWING.md](docs/FOLLOWING.md) for the following encoding and merge rules.

## License

Copyright 2017-2025 The Convex Foundation and Contributors

Code in convex-social is provided under the [Convex Public License](../LICENSE.md).
