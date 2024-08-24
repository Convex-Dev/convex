module convex.restapi {
	exports convex.restapi;
	exports convex.restapi.model;

	requires transitive convex.core;
	requires transitive convex.peer;
	requires transitive io.javalin;
	requires io.javalin.community.ssl;
	requires openapi.specification;
	requires javalin.openapi.plugin;
	requires javalin.redoc.plugin;
	requires javalin.swagger.plugin;
	// requires static com.fasterxml.jackson.databind;
	requires jetty.servlet.api;
	requires transitive kotlin.stdlib;
	requires kotlin.reflect;
	requires org.eclipse.jetty.server;
	
	requires org.slf4j;
	requires convex.java;
	// requires org.junit.jupiter.api;
	
	opens convex.restapi.pub; 
}