package convex.restapi;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import convex.api.Convex;
import convex.api.ConvexLocal;
import convex.core.data.AccountKey;
import convex.core.data.Address;
import convex.java.JSON;
import convex.peer.Server;
import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.InternalServerErrorResponse;
import io.javalin.http.ServiceUnavailableResponse;
import io.javalin.http.staticfiles.Location;

public class RESTServer {

	private Server server;
	private Convex convex;
	private Javalin app;

	private RESTServer() {
		app = Javalin.create(config -> {
			config.enableWebjars();
			config.enableCorsForAllOrigins();
			config.addStaticFiles(staticFiles -> {
				staticFiles.hostedPath = "/"; // change to host files on a subpath, like '/assets'
				staticFiles.directory = "/public"; // the directory where your files are located
				staticFiles.location = Location.CLASSPATH; // Location.CLASSPATH (jar) or Location.EXTERNAL (file
															// system)
				staticFiles.precompress = false; // if the files should be pre-compressed and cached in memory
													// (optimization)
				staticFiles.aliasCheck = null; // you can configure this to enable symlinks (=
												// ContextHandler.ApproveAliases())
				staticFiles.skipFileFunction = req -> false; // you can use this to skip certain files in the dir, based
																// on the HttpServletRequest
			});
		});

		addAPIRoutes();
	}

	private void addAPIRoutes() {
		app.post("/api/v1/createAccount", this::createAccount);

	}

	public void createAccount(Context ctx) {
		Map<String, Object> req;
		try {
			req = JSON.toMap(ctx.body());
		} catch (Exception e) {
			throw new BadRequestResponse(jsonError("Invalid JSON body"));
		}
		Object key = req.get("accountKey");
		if (key == null)
			throw new BadRequestResponse(jsonError("Expected JSON body containing 'accountKey' field"));

		AccountKey pk = AccountKey.parse(key);
		if (pk == null)
			throw new BadRequestResponse(jsonError("Unable to parse accountKey: " + key));

		Address a;
		try {
			a = convex.createAccountSync(pk);
		} catch (TimeoutException e) {
			throw new ServiceUnavailableResponse(jsonError("Timeout in request"));
		} catch (IOException e) {
			throw new InternalServerErrorResponse(jsonError(e.getMessage()));
		}
		ctx.result("{\"address\": " + a.toLong() + "}");
	}

	private static String jsonError(String string) {
		return "{\"error\":\"" + string + "\"}";
	}

	/**
	 * Create a RESTServer connected to a local Convex Peer Server instance.
	 * Defaults to using the Peer Controller account.
	 * 
	 * @param server
	 * @return
	 */
	public static RESTServer create(Server server) {
		RESTServer newServer = new RESTServer();
		newServer.server = server;
		newServer.convex = ConvexLocal.create(server, server.getPeerController(), server.getKeyPair());
		return newServer;
	}

	/**
	 * Create a RESTServer connected to a Convex Client instance. Defaults to using
	 * the Peer Controller account.
	 * 
	 * @param server
	 * @return
	 */
	public static RESTServer create(Convex convex) {
		RESTServer newServer = new RESTServer();
		newServer.convex = convex;
		return newServer;
	}

	public void start() {
		app.start();
	}
	
	public void start(int port) {
		app.start(port);
	}

	public void stop() {
		app.close();
	}

	public Convex getConvex() {
		return convex;
	}

	/**
	 * Gets the local Server instance, or null if not a local connection.
	 * 
	 * @return
	 */
	public Server getServer() {
		return server;
	}

	public int getPort() {
		return app.port();
	}
}
