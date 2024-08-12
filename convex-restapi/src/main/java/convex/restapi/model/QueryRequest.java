package convex.restapi.model;

import io.javalin.openapi.OpenApiByFields;

@OpenApiByFields
public class QueryRequest {
	public String address;
	public String source;
	public boolean raw;
}
