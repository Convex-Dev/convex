package convex.restapi;

import com.blade.mvc.annotation.GetRoute;
import com.blade.mvc.annotation.Path;
import com.blade.mvc.annotation.PathParam;
import com.blade.mvc.http.Response;


@Path
public class APIController {

	@GetRoute("/api/v1/accounts/:address")
	public void getAccount(Response response, @PathParam String address) {
		response.text("account info " + address);
	}
}
