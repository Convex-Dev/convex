package convex.restapi.model;

import io.javalin.openapi.OpenApiByFields;

@OpenApiByFields
public class JsonRPCRequest {
	String jsonrpc;
	String id;
	String method;
	Object params;

}
