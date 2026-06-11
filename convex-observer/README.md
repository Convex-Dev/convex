# Convex Observer

[![Maven Central](https://img.shields.io/maven-central/v/world.convex/convex-observer.svg?label=Maven%20Central)](https://search.maven.org/search?q=world.convex)
[![javadoc](https://javadoc.io/badge2/world.convex/convex-observer/javadoc.svg)](https://javadoc.io/doc/world.convex/convex-observer)

Observability tools for Convex peer operations. This module provides observers that
hook into a running [peer](../convex-peer/) `Server` to monitor transaction activity,
either by logging it or by streaming it to an external system.

## Overview

A peer's `TransactionHandler` exposes request and response observation hooks. An
observer supplies a request observer (called when a transaction is received) and a
response observer (called with the `Result` once it has been processed). This module
ships two implementations:

- **`LogObserver`** — logs each transaction request and response via SLF4J. Useful for
  development and lightweight monitoring.
- **`StrimziKafka`** — streams transaction events to an Apache Kafka topic (e.g. a
  Strimzi cluster) for downstream analytics and dashboards. Backed by an
  `AObserverQueue` so observation does not block peer processing.

## Installation

### Maven

```xml
<dependency>
    <groupId>world.convex</groupId>
    <artifactId>convex-observer</artifactId>
    <version>0.8.5</version>
</dependency>
```

### Gradle

```groovy
implementation 'world.convex:convex-observer:0.8.5'
```

## Usage

Attach an observer to a running peer `Server` via its `TransactionHandler`:

```java
import convex.observer.LogObserver;
import convex.peer.Server;
import convex.peer.TransactionHandler;

Server server = ...; // your running peer
TransactionHandler th = server.getTransactionHandler();

LogObserver observer = new LogObserver(server);
th.setRequestObserver(observer.getTransactionRequestObserver());
th.setResponseObserver(observer.getTransactionResponseObserver());
```

To stream to Kafka instead, swap in `StrimziKafka`:

```java
import convex.observer.StrimziKafka;

StrimziKafka observer = StrimziKafka.get(server);
th.setRequestObserver(observer.getTransactionRequestObserver(server));
th.setResponseObserver(observer.getTransactionResponseObserver(server));
```

Set either observer to `null` to stop observing.

> The Convex Desktop GUI exposes these observers interactively under its peer
> **Observer** panel (None / Strimzi / Logs).

## License

Copyright 2017-2025 The Convex Foundation and Contributors

Code in convex-observer is provided under the [Convex Public License](../LICENSE.md).
