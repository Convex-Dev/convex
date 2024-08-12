package convex.restapi.model;

import io.javalin.openapi.OpenApiByFields;

@OpenApiByFields
public class QueryAccountResponse {
	public long address;
	public long sequence;
	public long balance;
	public long allowance;
	public long memorySize;
	public String key;
	public String type;
}
