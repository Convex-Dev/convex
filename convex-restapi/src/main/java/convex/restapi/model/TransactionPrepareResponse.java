package convex.restapi.model;

import io.javalin.openapi.OpenApiByFields;

@OpenApiByFields
public class TransactionPrepareResponse {
	public String address;
	public String source;
	public long sequence;
	public String hash;
}
