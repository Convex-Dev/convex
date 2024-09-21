module convex.java {
	exports convex.java.asset;
	exports convex.java;

	requires transitive convex.core;
	requires json.simple;
	requires transitive org.apache.httpcomponents.client5.httpclient5;
	requires transitive org.apache.httpcomponents.core5.httpcore5;
	requires convex.peer;
}