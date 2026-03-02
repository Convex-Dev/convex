module convex.peer {
	exports convex.net;
	exports convex.peer;
	exports convex.peer.auth;
	exports convex.peer.signing;
	exports convex.net.store;
	exports convex.api;

	requires transitive convex.core;
	requires java.net.http;
	requires org.slf4j;
	requires transitive io.netty.common;
	requires io.netty.transport;
	requires io.netty.buffer;
	requires io.netty.codec;
}