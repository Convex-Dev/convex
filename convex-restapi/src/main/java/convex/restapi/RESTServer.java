package convex.restapi;

import java.io.Closeable;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;

import org.eclipse.jetty.server.ServerConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.Convex;
import convex.api.ConvexLocal;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.CertUtils;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.util.Utils;
import convex.peer.API;
import convex.peer.ConfigException;
import convex.peer.LaunchException;
import convex.peer.Server;
import convex.restapi.api.ChainAPI;
import convex.restapi.api.DLAPI;
import convex.restapi.api.DepAPI;
import convex.restapi.api.PeerAdminAPI;
import io.javalin.Javalin;
import io.javalin.community.ssl.SslPlugin;
import io.javalin.config.JavalinConfig;
import io.javalin.http.staticfiles.Location;
import io.javalin.openapi.JsonSchemaLoader;
import io.javalin.openapi.JsonSchemaResource;
import io.javalin.openapi.plugin.OpenApiPlugin;
import io.javalin.openapi.plugin.redoc.ReDocPlugin;
import io.javalin.openapi.plugin.swagger.SwaggerPlugin;
import io.javalin.util.JavalinBindException;

public class RESTServer implements Closeable {
	protected static final Logger log = LoggerFactory.getLogger(RESTServer.class.getName());

	protected final Server server;
	protected final Convex convex;
	protected Javalin javalin;

	private RESTServer(Server server) {
		this.server = server;
		this.convex = ConvexLocal.create(server, server.getPeerController(), server.getKeyPair());
	}
	
	private Javalin buildApp() {
		SslPlugin sslPlugin = getSSLPlugin(server.getConfig());
		Javalin app = Javalin.create(config -> {
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
				staticFiles.directory = "/convex/restapi/pub"; // Resource location in classpath
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
		return app;
	}



	// Get an SSL plugin, or null if SSL cannot be configured
	protected SslPlugin getSSLPlugin(HashMap<Keyword, Object> config) {
		SslPlugin sslPlugin=null;
		try {
			Path basePath=Utils.getHomePath().resolve(".convex/ssl");
			Path certFile=basePath.resolve("certificate.pem");
			Path privateFile=basePath.resolve("private.pem");
			if (Files.exists(certFile)&&Files.exists(privateFile)) {
				// Use provided files
			} else {
				basePath = Files.createTempDirectory("certs");
				String subjectDN="CN=localhost, O=o, L=L, ST=il, C=c";
				CertUtils.createCertificateFiles(subjectDN,basePath);
			}

			InputStream certS=Files.newInputStream(certFile);
			InputStream privateS=Files.newInputStream(privateFile);
			sslPlugin = new SslPlugin(conf -> {
				conf.pemFromInputStream(certS, privateS);
				conf.http2=true;
			});

		} catch (Exception e) {
			e.printStackTrace();
			log.warn("Failed to create SSL plugin, will use insecure HTTP only", e);
		}
		return sslPlugin;
	}

	protected void addOpenApiPlugins(JavalinConfig config) {
		String docsPath="openapi-plugin/openapi-default.json";
		
		config.registerPlugin(new OpenApiPlugin(pluginConfig -> {
			
            pluginConfig
            .withDocumentationPath(docsPath)
            .withDefinitionConfiguration((version, definition) -> {
                definition.withInfo(info -> {
					info.setTitle("Convex REST API");
					info.setVersion("0.7.0");
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

	protected ChainAPI chainAPI;
	protected DepAPI depAPI;
	protected DLAPI dlAPI;
	protected WebApp webApp;
	protected PeerAdminAPI peerAPI;

	private void addAPIRoutes(Javalin app) {
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
		if (port==null) port=8080;
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
			javalin=buildApp();
			start(javalin,port);
		} catch (JavalinBindException e) {
			if (port!=null) throw e; // only try again if port unspecified
			log.warn("Specified port "+port+"already in use, chosing another at random");
			close();
			
			port=0; // use random port
			javalin=buildApp();
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

	public Convex getConvex() {
		return convex;
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
	
	public static void main(String[] args) throws InterruptedException, ConfigException, LaunchException {
		HashMap<Keyword,Object> config=new HashMap<>();
		config.put(Keywords.KEYPAIR, AKeyPair.generate());
		config.put(Keyword.create("faucet"), true);
		Server s=API.launchPeer(config);
		System.out.println("Using Ed25519 seed:   "+s.getKeyPair().getSeed());
		System.out.println("Using peer port:      "+s.getPort());
		try (RESTServer rs=RESTServer.create(s)) {
			rs.start();
			System.out.println("Started on REST port: "+rs.getPort());
			
			while (s.isRunning()) {
				Thread.sleep(1000);
			}
		}
		System.out.println("Server shutting down.... bye!");
	}
}
