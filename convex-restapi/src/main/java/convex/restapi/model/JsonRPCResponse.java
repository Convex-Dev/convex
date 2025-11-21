package convex.restapi.model;

import io.javalin.openapi.OpenApiByFields;

@OpenApiByFields
public class JsonRPCResponse {

	public String jsonrpc;
	public Object result;
	public Object error;
	public String id;

}

