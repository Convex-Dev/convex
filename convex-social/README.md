# Convex Social

[![Maven Central](https://img.shields.io/maven-central/v/world.convex/convex-social.svg?label=Maven%20Central)](https://search.maven.org/search?q=world.convex)
[![javadoc](https://javadoc.io/badge2/world.convex/convex-social/javadoc.svg)](https://javadoc.io/doc/world.convex/convex-social)

A lattice-based, peer-to-peer social network built on Convex. Each user owns a
cryptographically signed feed that only they can write to. Nodes selectively replicate
feeds based on follow relationships, and timelines are built by merging followed feeds
by timestamp.

## Overview

The social lattice composes standard Convex lattice primitives into a two-level
structure:

```
SocialLattice (per-user, signed by owner):
  :feed    → IndexLattice<Blob, ACell>   8-byte timestamp keys, last-writer-wins per entry
  :profile → LWWLattice                  display name, bio, avatar, etc.
  :follows → MapLattice<ACell, ACell>    followed key → {active, timestamp}
```

Each user's data is wrapped in `SignedData` via an `OwnerLattice`, so only the owner's
Ed25519 key can sign updates and foreign data is rejected during merge. The base layer
is intentionally minimal and extensible — easy to layer applications and UI on top.

## Features

- Owner-signed feeds — only the holder of the key can post
- Follow-based selective replication between nodes
- Timelines constructed by merging followed feeds by timestamp
- Conflict-free merge of independently updated replicas (CRDT semantics)
- Cursor-based Java API with `fork()` / `sync()` for batched operations

## Installation

### Maven

```xml
<dependency>
    <groupId>world.convex</groupId>
    <artifactId>convex-social</artifactId>
    <version>0.8.13</version>
</dependency>
```

### Gradle

```groovy
implementation 'world.convex:convex-social:0.8.13'
```

## Usage

```java
import convex.social.Social;
import convex.core.crypto.AKeyPair;

AKeyPair keyPair = AKeyPair.generate();

// Standalone instance with its own cursor
Social social = Social.create(keyPair);
social.user(keyPair.getAccountKey()).feed().post("Hello!");

// Fork for batch operations, then sync back
Social forked = social.fork();
forked.user(keyPair.getAccountKey()).feed().post("Post 1");
forked.user(keyPair.getAccountKey()).feed().post("Post 2");
forked.sync();
```

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
Social social = Social.connect(application);
social.user(keyPair.getAccountKey()).feed().post("Hello");
application.sync();
```

`Social.connect(parent, keyPair)` is available when a child needs its own signing
context. The raw-cursor overload remains a low-level standalone adapter.

## Design

See [docs/SOCIAL_DESIGN.md](docs/SOCIAL_DESIGN.md) for the full lattice architecture.

## License

Copyright 2017-2025 The Convex Foundation and Contributors

Code in convex-social is provided under the [Convex Public License](../LICENSE.md).
