# Convex REST API

[![Maven Central](https://img.shields.io/maven-central/v/world.convex/convex-restapi.svg?label=Maven%20Central)](https://search.maven.org/search?q=world.convex)
[![javadoc](https://javadoc.io/badge2/world.convex/convex-restapi/javadoc.svg)](https://javadoc.io/doc/world.convex/convex-restapi)

HTTP REST API server for accessing the [Convex](https://convex.world) network via standard web protocols.

## Features

- **RESTful Interface** - Standard HTTP endpoints for queries and transactions
- **OpenAPI Support** - Auto-generated API documentation
- **Embeddable** - Integrate REST API into any JVM application
- **JSON Responses** - Web-friendly response format

## Installation

### Maven

```xml
<dependency>
    <groupId>world.convex</groupId>
    <artifactId>convex-restapi</artifactId>
    <version>0.8.18</version>
</dependency>
```

### Gradle

```groovy
implementation 'world.convex:convex-restapi:0.8.18'
```

## Usage

### Embedding in Your Application

```java
import convex.api.Convex;
import convex.core.crypto.AKeyPair;
import convex.peer.API;
import convex.peer.Server;
import convex.restapi.RESTServer;

// Launch a local peer and connect to it
AKeyPair keyPair = AKeyPair.generate();
Server peerServer = API.launchPeer();
Convex convex = Convex.connect(peerServer, peerServer.getPeerController(), keyPair);

// Start REST server
RESTServer rest = RESTServer.create(convex);
rest.start(8080);
```

### API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v1/query` | POST | Execute read-only query |
| `/api/v1/simulate` | POST | Simulate transaction accounting without signing or committing state |
| `/api/v1/transact` | POST | Submit a signed transaction, or a seed for server-side signing |
| `/api/v1/faucet` | POST | Request test funds (test networks) |
| `/api/v1/accounts/{address}` | GET | Get account information |
| `/api/v1/watch` | GET (SSE) | Watch a query result against finalised state (disabled by default) |
| `/api/v1/watch/logs` | GET (SSE) | Watch filtered finalised log events |

### Example Requests

**Query:**
```bash
curl -X POST http://localhost:8080/api/v1/query \
  -H "Content-Type: application/json" \
  -d '{"address": "#11", "source": "*balance*"}'
```

Ordinary queries work even when the selected account has no CVM coins. They see
its real balance and receive an execution Juice allowance independent of funds.
State changes are discarded, and the result reports Juice used without charging
transaction fees or purchasing memory.

Public REST and MCP queries use the peer's `query.maxJuice` setting (100,000 by
default), capped at the CVM transaction maximum. `query.maxConcurrent` and
`query.maxWaitMillis` bound concurrent work and the wait for capacity. Callers
cannot raise these limits through the query request.

**Simulate:**

`POST /api/v1/simulate` takes the same `address` and `source` fields as a
transaction, without a seed or signature:

```bash
curl -X POST http://localhost:8080/api/v1/simulate \
  -H "Content-Type: application/json" \
  -d '{"address": "#11", "source": "(+ 2 3)"}'
```

An optional non-negative integer `sequence` supplies the encoded transaction
sequence; omission or `null` uses the account's next sequence. Simulation retains
transaction balance and memory checks, so a zero-balance account returns a `JUICE`
error even when the equivalent ordinary query succeeds. The response is a normal
Result (HTTP 200, including CVM errors) with estimated costs. Malformed requests
return HTTP 400; exhausted public query capacity returns HTTP 503 with `LOAD`.
No state, balance or sequence changes are committed.

The endpoint also accepts an unsigned or signed transaction directly as binary
CAD3 (`application/cvx-raw`). Java clients already expose simulation using a
transaction payload:

```java
Invoke tx = Invoke.create(convex.getAddress(), convex.getSequence() + 1, "(+ 2 3)");
Result estimate = convex.simulateSync(tx); // or simulate(tx) for a future
```

Transaction simulations retain balance and memory checks and report estimated
fees. They obey the same public execution ceiling and never commit state.
The HTTP client sends these payloads as `application/cvx-raw`, preserving their
transaction type; `/query` accepts a binary CAD3 map with the same `:source` and
`:address` fields as its CVX text form.
Existing transaction-payload queries remain compatible. Simulation does not
validate signatures or sequence numbers, and estimates reflect the current peer
snapshot and execution ceiling rather than guaranteeing a later transaction's cost.

**Transaction:**
```bash
curl -X POST http://localhost:8080/api/v1/transact \
  -H "Content-Type: application/json" \
  -d '{"address": "#11", "source": "(transfer #42 1000)", "seed": "<64 hex characters>"}'
```

The JSON form of `transact` sends a private seed to the server. Cleartext HTTP
is accepted only from loopback by default; remote clients must use HTTPS. Use
locally signed transactions with `transaction/prepare` and `transaction/submit`
where possible so the private key never leaves the client.

## Documentation

- [Filtered log watching](LOG_WATCHING.md)
- [Query-result watching](QUERY_WATCHING.md)
- [Javadoc API Reference](https://javadoc.io/doc/world.convex/convex-restapi)
- [Convex Documentation](https://docs.convex.world)
- [API Specification](https://docs.convex.world/docs/convex-api)

## Building from Source

```bash
git clone https://github.com/Convex-Dev/convex.git
cd convex
./mvnw -B -T1C install -pl convex-restapi -am
```

## License

Copyright 2021-2025 The Convex Foundation and Contributors

Code in convex-restapi is provided under the Apache License v2.0.

Some dependencies from The Convex Foundation are licensed under the Convex Public License.
