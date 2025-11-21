module convex.java {
	exports convex.java.asset;
	exports convex.java;

	requires transitive convex.core;
	requires transitive convex.peer;
	requires java.net.http;
}