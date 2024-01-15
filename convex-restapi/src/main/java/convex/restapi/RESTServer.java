package convex.restapi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.api.ConvexLocal;
import convex.peer.Server;
import convex.restapi.api.ChainAPI;
import convex.restapi.api.DepAPI;
import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import io.javalin.http.staticfiles.Location;
import io.javalin.openapi.plugin.OpenApiPlugin;
import io.javalin.openapi.plugin.OpenApiPluginConfiguration;
import io.javalin.openapi.plugin.swagger.SwaggerConfiguration;
import io.javalin.openapi.plugin.swagger.SwaggerPlugin;

public class RESTServer {
	protected static final Logger log = LoggerFactory.getLogger(RESTServer.class.getName());

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
					// replacement for enableCorsForAllOrigins()
					corsConfig.anyHost();
				});
			});

			addOpenApiPlugins(config);

			config.staticFiles.add(staticFiles -> {
				staticFiles.hostedPath = "/";
				staticFiles.location = Location.CLASSPATH; // Specify resources from classpath
				staticFiles.directory = "/public"; // Resource location in classpath
				staticFiles.precompress = false; // if the files should be pre-compressed and cached in memory
													// (optimization)
				staticFiles.aliasCheck = null; // you can configure this to enable symlinks (=
												// ContextHandler.ApproveAliases())
				staticFiles.skipFileFunction = req -> false; // you can use this to skip certain files in the dir, based
																// on the HttpServletRequest
			});
		});

		app.exception(Exception.class, (e, ctx) -> {
			e.printStackTrace();
			String message = "Unexpected error: " + e;
			ctx.result(message);
			ctx.status(500);
		});

		addAPIRoutes();
	}

	protected void addOpenApiPlugins(JavalinConfig config) {
		OpenApiPluginConfiguration openApiConfiguration = getOpenApiConfig();
		config.plugins.register(new OpenApiPlugin(openApiConfiguration));

        SwaggerConfiguration swaggerConfiguration = new SwaggerConfiguration();
		config.plugins.register(new SwaggerPlugin(swaggerConfiguration));
	}

	protected OpenApiPluginConfiguration getOpenApiConfig() {
		return new OpenApiPluginConfiguration()
				.withDefinitionConfiguration((version, definition) -> definition.withOpenApiInfo((openApiInfo) -> {
					openApiInfo.setTitle("Convex REST API");
					openApiInfo.setVersion("0.1.1");
				})
				.withServer((openApiServer) -> {
					openApiServer.setUrl(("http://localhost:8080/"));
					openApiServer.setDescription("Local Convex REST Server");
					//openApiServer.addVariable("basePath", "", new String[] { "", "v1" }, "Base path of the server");
				}).withDefinitionProcessor(content -> { 
					return content.toPrettyString();
		        }));
	}

	protected ChainAPI chainAPI;
	protected DepAPI depAPI;

	private void addAPIRoutes() {
		chainAPI = new ChainAPI(this);
		chainAPI.addRoutes(app);

		depAPI = new DepAPI(this);
		depAPI.addRoutes(app);
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
		app.start();
	}

	public void start(Integer port) {
		if (port==null) {
			app.start();
		} else {
			app.start(port);
		}
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
