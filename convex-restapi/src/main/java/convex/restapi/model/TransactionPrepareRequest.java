package convex.restapi.model;

import io.javalin.openapi.OpenApiByFields;

@OpenApiByFields
public class TransactionPrepareRequest {
	public String address;
	public String source;
	public long sequence;
}
