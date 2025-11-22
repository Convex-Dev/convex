/**
 * Convex Java client library
 */
module convex.java {
	requires java.net.http;
	
	exports convex.java.asset;
	exports convex.java;

	requires transitive convex.core;
	requires transitive convex.peer;
}