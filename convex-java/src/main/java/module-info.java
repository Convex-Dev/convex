module convex.java {
	exports convex.java.asset;
	exports convex.java;

	requires transitive convex.core;
	requires json.simple;
	requires org.apache.httpcomponents.client5.httpclient5;
	requires org.apache.httpcomponents.core5.httpcore5;
}