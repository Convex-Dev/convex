package convex.restapi;

import convex.api.Convex;

import static io.javalin.apibuilder.ApiBuilder.*;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import convex.api.ConvexLocal;
import convex.core.data.AccountKey;
import convex.core.data.Address;
import convex.peer.Server;
import convex.java.JSON;
import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.InternalServerErrorResponse;
import io.javalin.http.ServiceUnavailableResponse;
import io.javalin.plugin.openapi.OpenApiOptions;
import io.javalin.plugin.openapi.OpenApiPlugin;
import io.javalin.plugin.openapi.annotations.OpenApi;
import io.javalin.plugin.openapi.annotations.OpenApiContent;
import io.javalin.plugin.openapi.annotations.OpenApiRequestBody;
import io.javalin.plugin.openapi.annotations.OpenApiResponse;
import io.javalin.plugin.openapi.dsl.OpenApiBuilder;
import io.javalin.plugin.openapi.dsl.OpenApiDocumentation;
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
				post("createAccount",this::createAccount);
			});
		});
	}
	
	OpenApiDocumentation createAccountDoc=OpenApiBuilder.document();
	
	@OpenApi(
		    requestBody = @OpenApiRequestBody(content=@OpenApiContent(type = "application/json")),
		    responses = { 
		        @OpenApiResponse(status=" 400",content=@OpenApiContent()),
		        @OpenApiResponse(status= "200",content=@OpenApiContent())
		    }
		)
	public void createAccount(Context ctx) {
		Map<String,Object> req;
		try {
			req=JSON.toMap(ctx.body()); 
		} catch (Exception e) {
			throw new BadRequestResponse(jsonError("Invalid JSON body"));
		}
		Object key=req.get("accountKey");
		if (key==null) throw new BadRequestResponse(jsonError("Expected JSON body containing 'accountKey' field"));
		
		AccountKey pk=AccountKey.parse(key);
		if (pk==null) throw new BadRequestResponse(jsonError("Unable to parse accountKey: "+key));
		Address a;
		try {
			a = convex.createAccountSync(pk);
		} catch (TimeoutException e) {
			throw new ServiceUnavailableResponse(jsonError("Timeout in request"));
		} catch (IOException e) {
			throw new InternalServerErrorResponse(jsonError(e.getMessage()));
		}
		ctx.result("{\"address\": "+a.toLong()+"}");
	}

	private static String jsonError(String string) {
		return "{\"error\":\""+string+"\"}";
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
