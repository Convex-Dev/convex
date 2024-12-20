module convex.peer {
	exports convex.net;
	exports convex.peer;
	exports convex.net.store;
	exports convex.api;

	requires transitive convex.core;
	requires java.net.http;
	requires org.slf4j;
	requires transitive io.netty.all;
	requires io.netty.transport;
	requires io.netty.buffer;
	requires io.netty.common;
	requires io.netty.codec;
}