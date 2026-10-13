package convex.restapi.model;

import io.javalin.openapi.OpenApiByFields;

/** Transaction simulation request; no signing credentials are required. */
@OpenApiByFields
public class SimulateRequest {
	public String address;
	public String source;
	/** Optional encoded sequence; defaults to the account's next sequence. */
	public Long sequence;
}
