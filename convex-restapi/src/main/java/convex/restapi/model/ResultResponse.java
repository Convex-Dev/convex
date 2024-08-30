package convex.restapi.model;

import io.javalin.openapi.OpenApiByFields;

@OpenApiByFields
public class ResultResponse {
	public String value;
	public String errorCode;
	public Object info;
}
