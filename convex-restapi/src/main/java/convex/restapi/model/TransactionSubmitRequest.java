package convex.restapi.model;

import io.javalin.openapi.OpenApiByFields;

@OpenApiByFields
public class TransactionSubmitRequest {
	public String hash;
	public String data;
	public String accountKey;
	public String sig;
}
