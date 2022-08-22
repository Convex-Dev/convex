# convex-restapi

HTTP REST API Server for Convex Peer functionality

## About

Convex is an open, realtime, decentralised technology for the Internet of Value. 

`convex-restapi` provides a running HTTP Server that can operate as a peer on the Convex network and/or serve requests from clients that access the Convex network.

## Usage

This module is not intended to be used as a standalone library. Rather, it allows a Convex REST Peer server to be instantiated within any application.

Example code:

```java
import convex.api.Convex;
import convex.peer.Server;
import convex.restapi.RESTServer;

public void launchRestAPI(int port) {
	Server peerServer = API.launchPeer();
	Convex convex = Convex.connect(peerServer, peerServer.getPeerController(), keyPair);
	RESTServer server=RESTServer.create(convex);
	server.start(port);
}
```

## License

Copyright 2021 The Convex Foundation

`convex-restapi` is licensed under the Apache License v2.0. 

Some dependencies from The Convex Foundation are licensed under the Convex Public License, which is an open source license that is free to use for any applications using the Convex Network.
