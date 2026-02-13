package convex.restapi;

import java.io.Closeable;
import java.util.HashMap;
import java.util.function.Consumer;

import org.eclipse.jetty.server.ServerConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.api.ConvexLocal;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.data.Keyword;
import convex.core.lang.RT;
import convex.core.util.Utils;
import convex.peer.API;
import convex.peer.ConfigException;
import convex.peer.LaunchException;
import convex.peer.Server;
import convex.peer.auth.PeerAuth;
import convex.restapi.api.ChainAPI;
import convex.restapi.api.DIDAPI;
import convex.restapi.api.DLAPI;
import convex.restapi.api.DepAPI;
import convex.restapi.api.X402;
import convex.restapi.auth.AuthMiddleware;
import convex.restapi.mcp.McpAPI;
import convex.restapi.web.ExplorerAPI;
import convex.restapi.web.PeerAdminAPI;
import convex.restapi.web.WebApp;
import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import io.javalin.http.staticfiles.Location;
import io.javalin.openapi.JsonSchemaLoader;
import io.javalin.openapi.JsonSchemaResource;
import io.javalin.openapi.OpenApiInfo;
import io.javalin.openapi.plugin.DefinitionConfiguration;
import io.javalin.openapi.plugin.OpenApiPlugin;
import io.javalin.openapi.plugin.redoc.ReDocPlugin;
import io.javalin.openapi.plugin.swagger.SwaggerPlugin;
import io.javalin.util.JavalinException;

/**
 * Operates a REST API server and web application connected to a local peer server
 */
public class RESTServer implements Closeable {
	protected static final Logger log = LoggerFactory.getLogger(RESTServer.class.getName());

	protected final Server server;
	protected final Convex convex;
	protected Javalin javalin;
	
	protected static final Integer DEFAULT_PORT=8080;

	private RESTServer(Server server) {
		this.server = server;
		this.convex = ConvexLocal.create(server);
		
		if (RT.bool(getConfig().get(ChainAPI.K_FAUCET))) {
			this.convexFaucet = ConvexLocal.create(server,server.getPeerController(),server.getKeyPair());
		} else {
			this.convexFaucet=null;
		}
	}
	
	protected ChainAPI chainAPI;
	protected DepAPI depAPI;
	protected DLAPI dlAPI;
	protected WebApp webApp;
	protected PeerAdminAPI peerAPI;
	protected ExplorerAPI explorerAPI;
	protected McpAPI mcpAPI;
	protected X402 x402API;
	protected DIDAPI didAPI;
	protected AuthMiddleware authMiddleware;

	public McpAPI getMcpAPI() {
		return mcpAPI;
	}

	public AuthMiddleware getAuthMiddleware() {
		return authMiddleware;
	}

	private void addAPIRoutes(Javalin app) {
		// Auth middleware — extracts identity from bearer token if present
		AKeyPair peerKP = server.getKeyPair();
		if (peerKP != null) {
			PeerAuth peerAuth = new PeerAuth(peerKP);
			authMiddleware = new AuthMiddleware(peerAuth);
			app.before(authMiddleware.handler());
		}

		chainAPI = new ChainAPI(this);
		chainAPI.addRoutes(app);

		depAPI = new DepAPI(this);
		depAPI.addRoutes(app);
		
		peerAPI = new PeerAdminAPI(this);
		peerAPI.addRoutes(app);

		webApp = new WebApp(this);
		webApp.addRoutes(app);

		dlAPI = new DLAPI(this);
		dlAPI.addRoutes(app);

		explorerAPI = new ExplorerAPI(this);
		explorerAPI.addRoutes(app);

		mcpAPI = new McpAPI(this);
		mcpAPI.addRoutes(app);

		x402API = new X402(this);
		x402API.addRoutes(app);

		didAPI = new DIDAPI(this);
		didAPI.addRoutes(app);
	}
	
	private Javalin buildApp(boolean useSSL) {
		Javalin app = Javalin.create(config -> {
			config.bundledPlugins.enableCors(cors -> {
				cors.addRule(corsConfig -> {
					// ?? corsConfig.allowCredentials=true;
					
					// replacement for enableCorsForAllOrigins()
					corsConfig.anyHost();
				});
			});
			
			
			addOpenApiPlugins(config);

			config.staticFiles.add(staticFiles -> {
				staticFiles.hostedPath = "/";
				staticFiles.location = Location.CLASSPATH; // Specify resources from classpath
				staticFiles.directory = "/convex/restapi/pub"; // Resource location in classpath
				staticFiles.precompress = false; // if the files should be pre-compressed and cached in memory
													// (optimization)
				staticFiles.aliasCheck = null; // you can configure this to enable symlinks (=
												// ContextHandler.ApproveAliases())
				staticFiles.skipFileFunction = req -> false; // you can use this to skip certain files in the dir, based
																// on the HttpServletRequest
			});
			
			config.useVirtualThreads=true;
		});
		


		app.exception(Exception.class, (e, ctx) -> {
			e.printStackTrace();
			String message = "Unexpected error: " + e;
			ctx.result(message);
			ctx.status(500);
		});
		
		
		app.options("/*", ctx-> {
			ctx.status(204); // No context#
			ctx.removeHeader("Content-type");
			ctx.header("access-control-allow-headers", "content-type");
			ctx.header("access-control-allow-methods", "GET,HEAD,PUT,PATCH,POST,DELETE");
			ctx.header("access-control-allow-origin", "*");
			ctx.header("vary","Origin, Access-Control-Request-Headers");
		});
		
		// Header to every response
		app.afterMatched(ctx->{
			// Reflect CORS origin
			String origin = ctx.req().getHeader("Origin");
			if (origin!=null) {
				ctx.header("access-control-allow-origin", "*");
			} else {
				ctx.header("access-control-allow-origin", "*");
			}
		});

		addAPIRoutes(app);	
		return app;
	}



	protected void addOpenApiPlugins(JavalinConfig config) {
		String docsPath="/openapi";
		
		config.registerPlugin(new OpenApiPlugin(pluginConfig -> {
            pluginConfig
            .withDocumentationPath(docsPath)
            .withDefinitionConfiguration((version, definition) -> {
            	DefinitionConfiguration def=definition;
                def=def.withInfo((Consumer <OpenApiInfo>)
                		info -> {
							info.setTitle("Convex REST API");
							info.setVersion(Utils.getVersion());
		                });
            });
		}));

		config.registerPlugin(new SwaggerPlugin(swaggerConfiguration->{
			swaggerConfiguration.setDocumentationPath(docsPath);
		}));
		config.registerPlugin(new ReDocPlugin(reDocConfiguration -> {
	        reDocConfiguration.setDocumentationPath(docsPath);
	    }));
		
		for (JsonSchemaResource generatedJsonSchema : new JsonSchemaLoader().loadGeneratedSchemes()) {
	        System.out.println(generatedJsonSchema.getName());
	    }
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
	
	protected void setupJettyServer(org.eclipse.jetty.server.Server jettyServer, Integer port) {
		if (port==null) port=DEFAULT_PORT;
		ServerConnector connector = new ServerConnector(jettyServer);
		connector.setPort(port);
		jettyServer.addConnector(connector);
	}

	/**
	 * Start app with default port
	 */
	public void start() {
		start(null);
	}

	/**
	 * Start app with specific port
	 */
	public synchronized void start(Integer port) {
		close();
		try {
			javalin=buildApp(true);
			start(javalin,port);
		} catch (JavalinException e) {
			if (port!=null) throw e; // only try again if port unspecified
			log.warn("Specified port "+port+"already in use, chosing another at random");
			close();
			
			port=0; // use random port
			javalin=buildApp(false);
			start(javalin,port);
		}
	}
	
	protected void start(Javalin app, Integer port) {
		org.eclipse.jetty.server.Server jettyServer=app.jettyServer().server();
		setupJettyServer(jettyServer,port);
		app.start();
	}

	public synchronized void close() {
		if (javalin!=null) javalin.stop();
		javalin=null;
		
		// we don't own the Convex server, so do nothing to it
	}

	/**
	 * Shared local Convex client instance. Use for fast reads. Don't transact with this!!
	 * @return Local convex client instance
	 */
	public Convex getConvex() {
		return convex;
	}
	
	private final Convex convexFaucet;
	/**
	 * Shared local Convex faucet instance. SECURITY: has access to faucet funds. Don't allow external usage!
	 * @return Local convex faucet instance
	 */
	public Convex getFaucet() {
		return convexFaucet;
	}


	/**
	 * Gets the local Convex Server instance, or null if not using a local connection.
	 * 
	 * @return Server instance, or null if not available.
	 */
	public Server getServer() {
		return server;
	}

	public int getPort() {
		if (javalin==null) throw new Error("Javalin not started");
		return javalin.port();
	}

	public HashMap<Keyword, Object> getConfig() {
		return server.getConfig();
	}
	
	/**
	 * Gets the base URL to use for external links. May be null
	 * @return BAse URL String
	 */
	public String getBaseURL() {
		Object o= server.getConfig().get(Keywords.BASE_URL);
		if (o instanceof String) return (String)o;
		return null;
	}

	/**
	 * Main function to run a test server instance locally
	 */
	public static void main(String[] args) throws InterruptedException, ConfigException, LaunchException {
		HashMap<Keyword,Object> config=new HashMap<>();
		AKeyPair kp=AKeyPair.createSeeded(88888888);
		config.put(Keywords.KEYPAIR, kp);
		config.put(Keyword.create("faucet"), true);
		Server s=API.launchPeer(config);
		System.out.println("Using Ed25519 seed:   "+s.getKeyPair().getSeed());
		System.out.println("Using peer port:      "+s.getPort());
		
		Convex c=Convex.connect(s);
		c.setAddress(Address.create(12), kp);
		c.transact("(log :TEST)");
		c.transact(":test-transaction");
		c.transact("(def TOKEN (deploy (@convex.fungible/build-token {:supply 1000000})))");
		c.transact("(@convex.asset/transfer #13 [TOKEN 1337])");
		
		try (RESTServer rs=RESTServer.create(s)) {
			rs.start();
			System.out.println("Started on REST port: "+rs.getPort());
			
			// Loop inside rest server instance
			while (s.isRunning()) {
				Thread.sleep(1000);
			}
		}
		System.out.println("Server shutting down.... bye!");
	}
}
