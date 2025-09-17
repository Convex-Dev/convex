package convex.restapi.model;

import java.util.List;

import io.javalin.openapi.*;

@OpenApiByFields
public class ResultResponse {
	public Object value;
	public String errorCode;
	public TransactionInfo info;
	
	@OpenApiByFields
	public static class TransactionInfo {
		public Long juice;
		public String tx;
		public String source;
		public Long fees;
		
		public List<Long> loc;
	}
}
