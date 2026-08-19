package convex.restapi;

import java.io.Closeable;
import java.util.HashMap;
import java.util.concurrent.Semaphore;

import org.eclipse.jetty.server.ServerConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.api.ConvexLocal;
import convex.core.Coin;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Format;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.util.Utils;
import convex.lattice.cursor.Root;
import convex.peer.API;
import convex.peer.ConfigException;
import convex.peer.LaunchException;
import convex.peer.Server;
import convex.peer.auth.PeerAuth;
import convex.peer.signing.SigningService;
import convex.restapi.api.AGenericAPI;
import convex.restapi.api.ChainAPI;
import convex.restapi.api.ConfirmAPI;
import convex.restapi.api.DIDAPI;
import convex.restapi.api.DLAPI;
import convex.restapi.api.DepAPI;
import convex.restapi.api.X402API;
import convex.restapi.api.LogWatchAPI;
import convex.restapi.api.QueryWatchAPI;
import convex.restapi.auth.AuthMiddleware;
import convex.restapi.auth.AdminAuthorizer;
import convex.restapi.auth.ConfirmationService;
import convex.restapi.auth.OAuthService;
import convex.restapi.auth.VenueIdentity;
import convex.restapi.handler.HttpMethodFilter;
import convex.restapi.mcp.McpAPI;
import convex.restapi.mcp.McpServer;
import convex.restapi.web.AuthPage;
import convex.x402.X402;
import convex.restapi.web.ExplorerAPI;
import convex.restapi.web.PeerAdminAPI;
import convex.restapi.web.WebApp;
import convex.api.ContentTypes;
import convex.core.util.JSON;
import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import io.javalin.config.RoutesConfig;
import io.javalin.http.HttpResponseException;
import io.javalin.http.ContentTooLargeResponse;
import io.javalin.http.HandlerType;
import io.javalin.http.ServiceUnavailableResponse;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.http.staticfiles.Location;
import io.javalin.openapi.JsonSchemaLoader;
import io.javalin.openapi.JsonSchemaResource;
import io.javalin.openapi.plugin.OpenApiPlugin;
import io.javalin.openapi.plugin.redoc.ReDocPlugin;
import io.javalin.openapi.plugin.swagger.SwaggerPlugin;
import io.javalin.util.JavalinBindException;

/**
 * Operates a REST API server and web application connected to a local peer server
 */
public class RESTServer implements Closeable {
	protected static final Logger log = LoggerFactory.getLogger(RESTServer.class.getName());

	protected final Server server;
	protected final RESTConfig restConfig;
	protected final Convex convex;
	protected final PublicQueryService publicQueryService;
	protected final AString venueDID;
	protected final AdminAuthorizer adminAuthorizer;
	/** Configured maximum size of a structured REST request body (bytes). */
	protected final long maxRequestBytes;
	/** Bytes of an oversized body to drain before rejecting; see {@link #drainRejectedBody}. */
	protected final long oversizeDrainBytes;
	/** Global admission control bounding concurrent short-lived requests. */
	protected final Semaphore requestPermits;
	protected Javalin javalin;

	/** Context attribute marking a request that holds a global admission permit. */
	private static final String REQUEST_PERMIT_ATTR="convex.rest.admitted";

	protected static final Integer DEFAULT_PORT=8080;
	/** Default hard ceiling for structured REST request bodies (see {@code rest.maxRequestBytes}). */
	public static final long MAX_REQUEST_BODY_BYTES=RESTConfig.DEFAULT_MAX_REQUEST_BYTES;
	/** Extra bytes drained beyond the body ceiling before a rejection is answered. */
	static final long DRAIN_SLACK_BYTES=65536L;
	public static final Keyword K_FAUCET_MAX=Keyword.intern("faucet-max");

	/** Config key: maximum concurrent transaction requests server-wide. */
	public static final Keyword K_TRANSACT_LIMIT=Keyword.intern("transact-limit");

	/** Config key: maximum concurrent transaction requests from any one client. */
	public static final Keyword K_TRANSACT_LIMIT_CLIENT=Keyword.intern("transact-limit-client");

	/**
	 * Default cap on concurrent transaction requests server-wide.
	 *
	 * <p>A permit is held for as long as the transaction takes to reach consensus, so
	 * this bounds how many callers can be waiting at once. Request handling runs on
	 * virtual threads, so a waiting request is cheap and this can be generous; raise it
	 * on a peer with the memory and bandwidth to serve more.</p>
	 */
	public static final int DEFAULT_TRANSACT_LIMIT=10000;

	/**
	 * Default cap on concurrent transaction requests from a single client, so that one
	 * caller cannot consume the whole server-wide allowance.
	 */
	public static final int DEFAULT_TRANSACT_LIMIT_CLIENT=100;

	private RESTServer(Server server, RESTConfig explicitConfig) {
		this.server = server;
		Object attachedConfig = server.getConfig().get(RESTConfig.CONFIG);
		this.restConfig = (explicitConfig != null)
				? explicitConfig
				: (attachedConfig instanceof RESTConfig rc
						? rc : RESTConfig.fromLegacy(server.getConfig()));
		this.convex = ConvexLocal.create(server);
		this.publicQueryService = new PublicQueryService(server, restConfig);
		this.maxRequestBytes = restConfig.getMaxRequestBytes();
		this.oversizeDrainBytes = maxRequestBytes + DRAIN_SLACK_BYTES;
		long maxRequests = restConfig.getMaxConcurrentRequests();
		this.requestPermits = new Semaphore((int) Math.min(Integer.MAX_VALUE, Math.max(1L, maxRequests)));
		String baseUrl=restConfig.getBaseUrl();
		this.venueDID=(baseUrl==null)?null:VenueIdentity.fromBaseUrl(baseUrl);
		if (restConfig.isAdminEnabled()) {
			if (venueDID==null) {
				throw new IllegalArgumentException("rest.baseUrl is required when REST administration is enabled");
			}
			if (server.getKeyPair()==null) {
				throw new IllegalArgumentException("REST administration requires a local Peer key pair");
			}
			this.adminAuthorizer=new AdminAuthorizer(server,venueDID,
				restConfig.getAdminKeys(),restConfig.getAdminTrustedProxies());
		} else {
			this.adminAuthorizer=null;
		}

		if (RT.bool(getConfig().get(ChainAPI.K_FAUCET))) {
			this.convexFaucet = ConvexLocal.create(server,server.getPeerController(),server.getKeyPair());
		} else {
			this.convexFaucet=null;
		}

		// Configurable faucet max (in copper). Default 1 Gold = 1,000,000,000 copper
		Object fmObj = getConfig().get(K_FAUCET_MAX);
		if (fmObj instanceof Number) {
			this.faucetMax = ((Number)fmObj).longValue();
		} else {
			this.faucetMax = Coin.GOLD;
		}

		this.transactLimit=positiveConfig(K_TRANSACT_LIMIT,DEFAULT_TRANSACT_LIMIT);
		this.transactLimitPerClient=positiveConfig(K_TRANSACT_LIMIT_CLIENT,DEFAULT_TRANSACT_LIMIT_CLIENT);

		AKeyPair kp = server.getKeyPair();
		if ((kp != null) && restConfig.isMcpEnabled() && restConfig.isSigningEnabled()) {
			Root<ACell> cursor = new Root<>();
			this.signingService = new SigningService(kp, cursor);
			this.signingService.init();
		} else {
			this.signingService = null;
		}
		// Elevated operations are a strict subset of signing. A stray elevated=true
		// must never expose confirmation or key-management routes by itself.
		this.confirmationService = (restConfig.isMcpEnabled() && restConfig.isSigningEnabled()
				&& restConfig.isElevatedEnabled())
				? new ConfirmationService() : null;
	}
	
	protected ChainAPI chainAPI;
	protected DepAPI depAPI;
	protected DLAPI dlAPI;
	protected WebApp webApp;
	protected PeerAdminAPI peerAPI;
	protected ExplorerAPI explorerAPI;
	protected McpServer mcpServer;
	protected McpAPI mcpAPI;
	protected X402API x402API;
	protected DIDAPI didAPI;
	protected LogWatchAPI logWatchAPI;
	protected QueryWatchAPI queryWatchAPI;
	protected AuthMiddleware authMiddleware;
	protected SigningService signingService;
	protected ConfirmationService confirmationService;
	protected volatile OAuthService oauthService;
	protected ConfirmAPI confirmAPI;
	protected AuthPage authPage;
	protected final long faucetMax;

	public long getFaucetMax() {
		return faucetMax;
	}

	/** Reads a positive integer setting, falling back to a default if absent or unusable. */
	private int positiveConfig(Keyword key, int defaultValue) {
		Object o=getConfig().get(key);
		if (o instanceof Number n) {
			int value=n.intValue();
			if (value>0) return value;
		}
		return defaultValue;
	}

	/**
	 * Gets the cap on concurrent transaction requests server-wide.
	 * @return Maximum concurrent transaction requests
	 */
	public int getTransactLimit() {
		return transactLimit;
	}

	/**
	 * Gets the cap on concurrent transaction requests from any one client.
	 * @return Maximum concurrent transaction requests per client
	 */
	public int getTransactLimitPerClient() {
		return transactLimitPerClient;
	}

	public McpServer getMcpServer() {
		return mcpServer;
	}

	public RESTConfig getRESTConfig() {
		return restConfig;
	}

	/** Gets the stable configured venue DID, or null when no base URL is configured. */
	public AString getVenueDID() {
		return venueDID;
	}

	/** Gets the administrator authorizer when administration is enabled. */
	public AdminAuthorizer getAdminAuthorizer() {
		return adminAuthorizer;
	}

	public PublicQueryService getPublicQueryService() {
		return publicQueryService;
	}

	public McpAPI getMcpAPI() {
		return mcpAPI;
	}

	public AuthMiddleware getAuthMiddleware() {
		return authMiddleware;
	}

	public SigningService getSigningService() {
		return signingService;
	}

	public ConfirmationService getConfirmationService() {
		return confirmationService;
	}

	public OAuthService getOAuthService() {
		OAuthService service=oauthService;
		if (service!=null) return service;
		synchronized (this) {
			service=oauthService;
			if (service==null) {
				service=new OAuthService(this);
				oauthService=service;
			}
		}
		return service;
	}

	private void addAPIRoutes(RoutesConfig routes) {
		// Authentication is optional for public Peers (the default). Operators may
		// explicitly make dynamic service surfaces private while leaving the small
		// authentication/bootstrap set reachable.
		AKeyPair peerKP = server.getKeyPair();
		if (peerKP != null) {
			PeerAuth peerAuth = new PeerAuth(peerKP);
			authMiddleware = new AuthMiddleware(peerAuth);
			routes.before(ctx -> {
				authMiddleware.authenticate(ctx);
				if (!restConfig.isPublicAccess()
						&& (AuthMiddleware.getIdentity(ctx) == null)
						&& !isAuthenticationBootstrap(ctx)) {
					throw new UnauthorizedResponse("Authentication required");
				}
			});
		} else if (!restConfig.isPublicAccess()) {
			throw new IllegalStateException("Private REST access requires a Peer key pair");
		}

		chainAPI = new ChainAPI(this);
		chainAPI.addRoutes(routes);

		logWatchAPI = new LogWatchAPI(this);
		logWatchAPI.addRoutes(routes);

		if (restConfig.isQueryWatchEnabled()) {
			queryWatchAPI = new QueryWatchAPI(this);
			queryWatchAPI.addRoutes(routes);
		}

		depAPI = new DepAPI(this);
		depAPI.addRoutes(routes);
		
		if (restConfig.isAdminEnabled()) {
			peerAPI = new PeerAdminAPI(this);
			peerAPI.addRoutes(routes);
		}

		if (restConfig.isMcpEnabled()) {
			mcpServer = new McpServer(Maps.of(
				"name", "convex-mcp",
				"title", "Convex MCP",
				"version", Utils.getVersion()
			));
			mcpAPI = new McpAPI(this, mcpServer);
			mcpServer.addRoutes(routes);
			mcpAPI.addRoutes(routes);
		}

		webApp = new WebApp(this);
		webApp.addRoutes(routes);

		dlAPI = new DLAPI(this);
		dlAPI.addRoutes(routes);

		explorerAPI = new ExplorerAPI(this);
		explorerAPI.addRoutes(routes);

		if (restConfig.isX402Enabled()) {
			x402API = new X402API(this);
			x402API.addRoutes(routes);
		}

		didAPI = new DIDAPI(this);
		didAPI.addRoutes(routes);

		if (confirmationService != null) {
			confirmAPI = new ConfirmAPI(this);
			confirmAPI.addRoutes(routes);
		}

		authPage = new AuthPage(this);
		authPage.addRoutes(routes);
	}

	private boolean isAuthenticationBootstrap(io.javalin.http.Context ctx) {
		if (ctx.method() == io.javalin.http.HandlerType.OPTIONS) return true;
		String path = ctx.path();
		if (path.equals("/auth") || path.startsWith("/auth/")) return true;
		if (path.equals("/confirm")) return true;
		if (path.equals("/.well-known/did.json")) return true;
		if (path.startsWith("/did/") || path.endsWith("/did.json")) return true;
		return path.endsWith(".css") || path.endsWith(".js") || path.endsWith(".png")
				|| path.endsWith(".svg") || path.endsWith(".ico");
	}
	
	private Javalin buildApp(Integer port) {
		int bindPort = (port == null) ? DEFAULT_PORT : port;
		Javalin app = Javalin.create(config -> {
			// RequestBody relies on this limit to bound chunked as well as fixed-length bodies.
			config.http.maxRequestSize=maxRequestBytes;

			config.bundledPlugins.enableCors(cors -> {
				cors.addRule(corsConfig -> {
					java.util.Set<String> origins = restConfig.getCorsAllowedOrigins();
					if (origins == null) {
						corsConfig.anyHost();
					} else if (!origins.isEmpty()) {
						String[] configured = origins.toArray(String[]::new);
						corsConfig.allowHost(configured[0], java.util.Arrays.copyOfRange(configured, 1, configured.length));
					}
					// Browser x402 clients can only read custom response headers that
					// CORS explicitly exposes
					corsConfig.exposeHeader(X402.HEADER_PAYMENT_REQUIRED);
					corsConfig.exposeHeader(X402.HEADER_PAYMENT_RESPONSE);
				});
			});


			addOpenApiPlugins(config);

			config.staticFiles.add(staticFiles -> {
				staticFiles.hostedPath = "/";
				staticFiles.location = Location.CLASSPATH; // Specify resources from classpath
				staticFiles.directory = "/convex/restapi/pub"; // Resource location in classpath
				staticFiles.aliasCheck = null; // you can configure this to enable symlinks (=
												// ContextHandler.ApproveAliases())
				staticFiles.skipFileFunction = req -> false; // you can use this to skip certain files in the dir, based
																// on the HttpServletRequest
			});

			config.concurrency.useVirtualThreads=true;

			HttpMethodFilter.install(config);

			config.jetty.addConnector((jettyServer, httpConfig) -> {
				// 1 acceptor + 1 selector: request handling uses virtual threads
				ServerConnector connector = new ServerConnector(jettyServer, 1, 1);
				connector.setPort(bindPort);
				return connector;
			});

			addHandlers(config.routes);
		});
		return app;
	}

	/**
	 * Reads and discards a bounded prefix of a request body we are about to reject, so the
	 * connection can be closed gracefully and the 413 survives.
	 *
	 * <p>Closing a connection that still holds unread body data resets it rather than
	 * closing gracefully, and a reset makes the client's TCP stack discard its receive
	 * buffer — including the 413 already written. Draining first lets the close be graceful
	 * so the status actually arrives. Bounded (body ceiling plus {@link #DRAIN_SLACK_BYTES})
	 * so a rejected request never costs more to read than a legal one.</p>
	 *
	 * <p>Does nothing when the client offered {@code Expect: 100-continue}: it is waiting
	 * for permission and has sent no body, so there is nothing to drain — and touching the
	 * stream would make Jetty invite it to send the very body being rejected.</p>
	 *
	 * @param ctx Request context for the rejected request
	 * @param oversizeDrainBytes Maximum bytes to read and discard
	 */
	private static void drainRejectedBody(io.javalin.http.Context ctx, long oversizeDrainBytes) {
		String expect=ctx.header("Expect");
		if ((expect!=null)&&expect.toLowerCase().contains("100-continue")) return;
		try {
			java.io.InputStream in=ctx.req().getInputStream();
			byte[] buffer=new byte[8192];
			long remaining=oversizeDrainBytes;
			while (remaining>0) {
				int n=in.read(buffer,0,(int)Math.min(buffer.length,remaining));
				if (n<0) break;
				remaining-=n;
			}
		} catch (java.io.IOException e) {
			// Client vanished mid-drain. Answering it is best-efforts by nature, and the
			// 413 below still runs; nothing here should mask the rejection.
		}
	}

	/**
	 * Whether a request is a long-lived stream excluded from the global admission cap.
	 * The query-watch and log-watch SSE endpoints hold their connection open, and the MCP
	 * SSE stream (the GET on {@code /mcp}) keeps its own connection cap (#659); short-lived
	 * MCP POST/DELETE calls are still counted.
	 *
	 * @param ctx Request context
	 * @return true if the request is a long-lived stream
	 */
	private static boolean isStreamingRequest(io.javalin.http.Context ctx) {
		String path=ctx.path();
		if (path==null) return false;
		if (path.equals("/api/v1/watch") || path.equals("/api/v1/watch/logs")) return true;
		if (path.equals("/mcp")) return ctx.method()==HandlerType.GET;
		return false;
	}

	private void addHandlers(RoutesConfig routes) {
		// Global admission control: bound concurrent short-lived requests so a flood
		// cannot pile up unbounded work. Long-lived SSE streams keep their own
		// connection limits and are excluded. Runs first so the permit is held for the
		// whole request; the after handler releases it even when a later stage throws.
		routes.before(ctx -> {
			if (isStreamingRequest(ctx)) return;
			if (!requestPermits.tryAcquire()) {
				throw new ServiceUnavailableResponse("Server busy: too many concurrent requests");
			}
			ctx.attribute(REQUEST_PERMIT_ATTR, Boolean.TRUE);
		});
		routes.after(ctx -> {
			if (ctx.attribute(REQUEST_PERMIT_ATTR)!=null) requestPermits.release();
		});

		// Reject a known oversized body before a handler starts parsing it. Requests
		// without Content-Length are still bounded while read by RequestBody.
		routes.before(ctx -> {
			long contentLength=ctx.req().getContentLengthLong();
			if (contentLength>maxRequestBytes) {
				drainRejectedBody(ctx, oversizeDrainBytes);
				throw new ContentTooLargeResponse("Request body exceeds maximum size of "
						+maxRequestBytes+" bytes");
			}
		});

		// Custom handler for HTTP error responses (BadRequestResponse, NotFoundResponse, etc.)
		// Produces consistent output in the requested content type: {:error "message"} or {"error":"message"}
		routes.exception(HttpResponseException.class, (e, ctx) -> {
			ctx.status(e.getStatus());
			String msg=e.getMessage();
			if (msg==null) msg="Error";
			String type=AGenericAPI.calcResponseContentType(ctx);
			if (type.equals(ContentTypes.CVX_RAW)) {
				ctx.contentType(ContentTypes.CVX_RAW);
				ACell body=Maps.of(Keywords.ERROR, Strings.create(msg));
				ctx.result(Format.encodeMultiCell(body, true).getBytes());
			} else if (type.equals(ContentTypes.CVX)) {
				ctx.contentType(ContentTypes.CVX);
				ACell body=Maps.of(Keywords.ERROR, Strings.create(msg));
				ctx.result(RT.print(body).toString());
			} else if (type.equals(ContentTypes.TEXT)) {
				ctx.contentType(ContentTypes.TEXT);
				ctx.result(msg);
			} else {
				ctx.contentType(ContentTypes.JSON);
				HashMap<String,Object> body=new HashMap<>();
				body.put("error", msg);
				ctx.result(JSON.toString(body));
			}
		});

		routes.exception(Exception.class, (e, ctx) -> {
			e.printStackTrace();
			String message = "Unexpected error: " + e;
			ctx.result(message);
			ctx.status(500);
		});

		addAPIRoutes(routes);
	}



	protected void addOpenApiPlugins(JavalinConfig config) {
		String docsPath="/openapi";
		
		config.registerPlugin(new OpenApiPlugin(pluginConfig -> {
            pluginConfig
            .withDocumentationPath(docsPath)
            .withDefinitionConfiguration((version, schema) -> {
                schema.info(info -> {
					info.title("Convex REST API");
					info.version(Utils.getVersion());
                });
            });
		}));

		config.registerPlugin(new SwaggerPlugin(swaggerConfiguration->{
			swaggerConfiguration.documentationPath = docsPath;
		}));
		config.registerPlugin(new ReDocPlugin(reDocConfiguration -> {
	        reDocConfiguration.documentationPath = docsPath;
	    }));
		
		if (log.isDebugEnabled()) {
			for (JsonSchemaResource generatedJsonSchema : new JsonSchemaLoader().loadGeneratedSchemes()) {
				log.debug("Loaded JSON schema: {}", generatedJsonSchema.getName());
			}
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
		RESTServer newServer = new RESTServer(server, null);
		return newServer;
	}

	/**
	 * Creates a REST server with an explicit typed configuration.
	 * @param server Peer server
	 * @param config REST runtime configuration
	 * @return New REST server
	 */
	public static RESTServer create(Server server, RESTConfig config) {
		if (config == null) throw new IllegalArgumentException("RESTConfig required");
		return new RESTServer(server, config);
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
		if (port == null) port = restConfig.getRestPort();
		try {
			javalin=buildApp(port);
			javalin.start();
		} catch (JavalinBindException e) {
			if (port!=null) throw e; // only try again if port unspecified
			log.warn("Default port "+DEFAULT_PORT+" already in use ("+e.getMessage()+"), choosing another at random");
			close();

			javalin=buildApp(0); // use random port
			javalin.start();
		}
	}

	public synchronized void close() {
		if (mcpAPI!=null) mcpAPI.shutdown();
		if (logWatchAPI!=null) logWatchAPI.shutdown();
		if (queryWatchAPI!=null) queryWatchAPI.shutdown();
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

	private final int transactLimit;

	private final int transactLimitPerClient;
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
		return restConfig.getBaseUrl();
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
