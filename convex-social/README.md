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
    <version>0.8.9</version>
</dependency>
```

### Gradle

```groovy
implementation 'world.convex:convex-social:0.8.9'
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
between nodes are convex-p2p's job, not its own. A social node is therefore a P2P node
plus the social region:

```java
KeyedLattice root = P2PLattice.ROOT.addLattice(Social.KEY_SOCIAL, Social.SOCIAL_LATTICE);
```

The social lattice is not part of any root by default — neither convex-p2p's nor
convex-core's `Lattice.ROOT` — so a node opts in by composing it, and the dependency runs
one way: convex-social depends on the P2P layer, never the reverse. Peers that do not
serve `:social` ignore the region rather than failing on it, so social can be deployed to
part of a network without coordinating an upgrade across all of it.

Composing onto `Lattice.ROOT` instead is equally valid where the application regions
(`:data`, `:fs`, `:kv`, `:queue`) are wanted alongside it.

A `Social` instance is then connected to the node's root cursor so that writes propagate
up for lattice push/pull:

```java
Social social = Social.connect(rootCursor, keyPair);
```

## Design

See [docs/SOCIAL_DESIGN.md](docs/SOCIAL_DESIGN.md) for the full lattice architecture.

## License

Copyright 2017-2025 The Convex Foundation and Contributors

Code in convex-social is provided under the [Convex Public License](../LICENSE.md).
