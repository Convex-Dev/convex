package convex.restapi;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.api.ConvexLocal;
import convex.core.data.Keyword;
import convex.core.util.Utils;
import convex.peer.Server;
import convex.restapi.api.ChainAPI;
import convex.restapi.api.DepAPI;
import io.javalin.Javalin;
import io.javalin.community.ssl.SslPlugin;
import io.javalin.config.JavalinConfig;
import io.javalin.http.staticfiles.Location;
import io.javalin.openapi.plugin.OpenApiPlugin;
import io.javalin.openapi.plugin.redoc.ReDocPlugin;
import io.javalin.openapi.plugin.swagger.SwaggerPlugin;

public class RESTServer {
	protected static final Logger log = LoggerFactory.getLogger(RESTServer.class.getName());

	protected final Server server;
	protected final Convex convex;
	protected final Javalin app;

	private RESTServer(Server server) {
		this.server = server;
		this.convex = ConvexLocal.create(server, server.getPeerController(), server.getKeyPair());

		SslPlugin sslPlugin = getSSLPlugin(server.getConfig());
		
		app = Javalin.create(config -> {
			config.staticFiles.enableWebjars();
			config.bundledPlugins.enableCors(cors -> {
				cors.addRule(corsConfig -> {
					// replacement for enableCorsForAllOrigins()
					corsConfig.anyHost();
				});
			});
			
			if (sslPlugin!=null) {
				config.registerPlugin(sslPlugin);
			}
			
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

		addAPIRoutes(app);	
	}

	// Get an SSL plugin, or null if SSL cannot be configured
	protected SslPlugin getSSLPlugin(HashMap<Keyword, Object> config) {
		boolean useSSL=true;
		SslPlugin sslPlugin=null;
		if (useSSL) try {
			Path certFile=Utils.getHomePath().resolve(".convex/ssl/certificate.pem");
			Path privateFile=Utils.getHomePath().resolve(".convex/ssl/private.pem");
			if (Files.exists(certFile)&&Files.exists(privateFile)) {
				InputStream certS=Files.newInputStream(certFile);
				InputStream privateS=Files.newInputStream(privateFile);
				sslPlugin = new SslPlugin(conf -> {
					conf.pemFromInputStream(certS, privateS);
				});
			} else {
				log.warn("Failed to find SSL cerificates, defaulting back to HTTP");
			}
		} catch (Exception e) {
			log.warn("Failed to create SSL plugin, will use insecure HTTP only", e);
		}
		return sslPlugin;
	}

	protected void addOpenApiPlugins(JavalinConfig config) {
		config.registerPlugin(new OpenApiPlugin(pluginConfig -> {
			
            pluginConfig.withDefinitionConfiguration((version, definition) -> {
                definition.withOpenApiInfo(info -> {
					info.setTitle("Convex REST API");
					info.setVersion("0.1.1");
                });
            });
		}));

		config.registerPlugin(new SwaggerPlugin());
		config.registerPlugin(new ReDocPlugin());
	}

	protected ChainAPI chainAPI;
	protected DepAPI depAPI;

	private void addAPIRoutes(Javalin app) {
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
		app.stop();
		// app.close(); // Gone In Javalin 6?
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
