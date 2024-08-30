package convex.restapi.model;

import io.javalin.openapi.OpenApiByFields;

@OpenApiByFields
public class FaucetRequest {
	public String address;
	public Object amount;
}
