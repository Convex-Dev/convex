package convex.restapi;

import java.io.IOException;
import java.lang.InterruptedException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

import com.blade.mvc.annotation.GetRoute;
import com.blade.mvc.annotation.Path;
import com.blade.mvc.annotation.PathParam;
import com.blade.mvc.annotation.PostRoute;
import com.blade.mvc.http.Request;
import com.blade.mvc.http.Response;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;


import convex.core.Constants;
import convex.core.Result;
import convex.core.State;
import convex.core.data.AccountStatus;
import convex.core.data.Address;



@Path
public class APIController {

	protected long timeout = Constants.DEFAULT_CLIENT_TIMEOUT;

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
	public void getAccount(Response response, @PathParam long address) {
		try {
			Future<State> futureState = APIServer.convex.acquireState();
			State state = futureState.get(timeout, TimeUnit.MILLISECONDS);
			Address accountAddress = Address.create(address);
			AccountStatus status = state.getAccount(accountAddress);
			System.out.println("account status " + status);

			String queryString = "(account "+accountAddress+")";
			Result result = APIServer.convex.querySync(queryString);
			System.out.println("query " + result.getValue());
		}
		catch (IOException | TimeoutException | InterruptedException | ExecutionException e) {
			System.out.println("error");
		}
		response.text("account info " + address);
	}
}
