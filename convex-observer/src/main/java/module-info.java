/**
 * Convex Observer - lightweight observability for Convex peers
 *
 * @author Mike Anderson and Contributors
 */
module convex.observer {
	requires java.base;  
	requires java.net.http;
	
	requires transitive convex.core;  
	requires transitive convex.peer;  
	
	requires org.slf4j;
}