package convex.restapi;

import convex.api.Convex;

import static io.javalin.apibuilder.ApiBuilder.*;
import convex.api.ConvexLocal;
import convex.peer.Server;
import io.javalin.Javalin;
import io.javalin.plugin.openapi.OpenApiOptions;
import io.javalin.plugin.openapi.OpenApiPlugin;
import io.javalin.plugin.openapi.ui.SwaggerOptions;
import io.swagger.v3.oas.models.info.Info;

public class RESTServer {

	private Server server;
	private Convex convex;
	private Javalin app;
	
	private RESTServer() {
		app=Javalin.create(config->{
			 config.registerPlugin(new OpenApiPlugin(getOpenApiOptions()));
		});
		
		app.get("/", ctx->{
			ctx.result("Convex Peer REST Server");
		});
		
		addAPIRoutes();
	}

	private OpenApiOptions getOpenApiOptions() {
		Info applicationInfo = new Info()
		        .version("1.0")
		        .description("Convex REST Server");
		return new OpenApiOptions(applicationInfo)
				.path("/swagger-docs")
				.swagger(new SwaggerOptions("/swagger").title("Convex Swagger Documentation"));
	}

	private void addAPIRoutes() {
		app.routes(()->{
			path("api/v1",()->{
				post("createAccount",ctx->{
					ctx.result("Create account");
				});
			});
		});
	}

	/**
	 * Create a RESTServer connected to a local Convex Peer Server instance. Defaults to using the Peer Controller account.
	 * 
	 * @param server
	 * @return
	 */
	public static RESTServer create(Server server) {
		RESTServer newServer=new RESTServer();
		newServer.server=server;
		newServer.convex=ConvexLocal.create(server, server.getPeerController(), server.getKeyPair());
		return newServer;
	}
	
	/**
	 * Create a RESTServer connected to a Convex Client instance. Defaults to using the Peer Controller account.
	 * 
	 * @param server
	 * @return
	 */
	public RESTServer create(Convex convex) {
		RESTServer newServer=new RESTServer();
		newServer.convex=convex;
		return newServer;
	}
	
	public void start() {
		app.start();
		
	}
	
	public void stop() {
		app.close();
	}
	
	public Convex getConvex() {
		return convex;
	}
	
	/**
	 * Gets the local Server instance, or null if not a local connection. 
	 * @return
	 */
	public Server getServer() {
		return server;
	}
}
