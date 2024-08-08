package convex.restapi.model;

import io.javalin.openapi.OpenApiByFields;

@OpenApiByFields
public class CreateAccountRequest {
	public String accountKey;
}
