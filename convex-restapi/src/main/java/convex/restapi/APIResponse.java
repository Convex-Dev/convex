package convex.restapi;

import com.hellokaton.blade.mvc.http.Response;
import com.hellokaton.blade.mvc.WebContext;

import org.json.simple.JSONObject;

// import lombok.Data;


public class APIResponse {

	static final String CODE_BAD_REQUEST = "UNKNOWN";
	static final String CODE_NOT_FOUND = "NOBODY";


	public static Response ok(JSONObject value) {
		var response = WebContext.response();
		response.json(value.toJSONString());
		response.status(200);
		return response;
	}

	static public Response failNotFound(String value) {
		return APIResponse.fail(404, CODE_NOT_FOUND, value);
	}

	static public Response failBadRequest(String value) {
		return APIResponse.fail(500, CODE_BAD_REQUEST, value);
	}

	static public Response fail(int statusCode, String code, String value) {
		var response = WebContext.response();
		JSONObject error = new JSONObject();
		error.put("errorCode", code);
		error.put("source", "Server");
		error.put("value", value);
		response.json(error.toJSONString());
		response.status(statusCode);
		return response;
	}


}
