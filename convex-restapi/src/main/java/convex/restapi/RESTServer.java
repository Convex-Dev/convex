package convex.restapi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.api.ConvexLocal;
import convex.peer.Server;
import convex.restapi.api.ConvexAPI;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.javalin.util.JavalinBindException;

public class RESTServer {
	private static final Logger log = LoggerFactory.getLogger(RESTServer.class.getName());

	protected final Server server;
	protected final Convex convex;
	protected final Javalin app;

	private RESTServer(Server server) {
		this.server = server;
		this.convex = ConvexLocal.create(server, server.getPeerController(), server.getKeyPair());

		app = Javalin.create(config -> {
			config.staticFiles.enableWebjars();
			config.plugins.enableCors(cors -> {
			    cors.add(corsConfig -> {
			        //replacement for enableCorsForAllOrigins()
			        corsConfig.anyHost();
			    });
			});
			config.staticFiles.add(staticFiles -> {
				staticFiles.hostedPath = "/"; 
				staticFiles.location = Location.CLASSPATH; // Specify resources from classpath
				staticFiles.directory = "/public"; // Resource location in classpath
				staticFiles.precompress = false; // if the files should be pre-compressed and cached in memory (optimization)
				staticFiles.aliasCheck = null; // you can configure this to enable symlinks (= ContextHandler.ApproveAliases())
				staticFiles.skipFileFunction = req -> false; // you can use this to skip certain files in the dir, based on the HttpServletRequest
			});
		});
		
		app.exception(Exception.class, (e, ctx) -> {
			e.printStackTrace();
			String message="Unexpected error: "+e;
		    ctx.result(message);
		    ctx.status(500);
		});

		addAPIRoutes();
	}

	protected ConvexAPI convexAPI;
	private void addAPIRoutes() {
		convexAPI=new ConvexAPI(this); 
		convexAPI.addRoutes(app,"/api");

	}
	



	/**
	 * Create a RESTServer connected to a local Convex Peer Server instance.
	 * Defaults to using the Peer Controller account.
	 * 
	 * @param server Server instance
	 * @return New {@link RESTServer} instance
	 */
	public static RESTServer create(Server server) {
		RESTServer newServer = new RESTServer(server);
		return newServer;
	}

	/**
	 * Create a RESTServer connected to a Convex Client instance. Defaults to using
	 * the Peer Controller account.
	 * 
	 * @param convex Convex client connection instance
	 * @return New {@link RESTServer} instance
	 */
	public static RESTServer create(Convex convex) {
		return create(convex.getLocalServer());
	}

	public void start() {
		try {
			app.start();
		} catch (JavalinBindException e) {
			log.warn("Unable to start REST Server: port already in use");
		}
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
	 * @return Server instance, or null if not available.
	 */
	public Server getServer() {
		return server;
	}

	public int getPort() {
		return app.port();
	}
}
