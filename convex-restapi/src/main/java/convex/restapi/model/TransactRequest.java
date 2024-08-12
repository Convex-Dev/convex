package convex.restapi.model;

import io.javalin.openapi.OpenApiByFields;

@OpenApiByFields
public class TransactRequest {
	public String source;
	public String seed;
	public String address;
}
