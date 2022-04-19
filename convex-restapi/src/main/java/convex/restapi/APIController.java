package convex.restapi;

import com.blade.mvc.annotation.GetRoute;
import com.blade.mvc.annotation.Path;
import com.blade.mvc.annotation.PathParam;
import com.blade.mvc.annotation.PostRoute;
import com.blade.mvc.http.Request;
import com.blade.mvc.http.Response;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;


@Path
public class APIController {

	@PostRoute("/api/v1/createAccount")
	public void createAccount(Request request, Response response) {
		String bodyString = request.bodyToString();
		System.out.println("body string " + bodyString);
		JSONObject obj;
		JSONParser parser = new JSONParser();
		try {
			JSONObject result = (JSONObject) parser.parse(bodyString);
			obj = new JSONObject(result);
		} catch (ParseException e) {
			throw new Error("Error in JSON parsing: " + e.getMessage(), e);
		}
		String value = (String) obj.get("accountKey");
		System.out.println("account key " + value);
	}

	@GetRoute("/api/v1/accounts/:address")
	public void getAccount(Response response, @PathParam String address) {
		response.text("account info " + address);
	}
}
