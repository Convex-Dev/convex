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
    <version>0.8.16</version>
</dependency>
```

### Gradle

```groovy
implementation 'world.convex:convex-restapi:0.8.16'
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
