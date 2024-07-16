package convex.restapi.api;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Iterator;

import convex.dlfs.DLFS;
import convex.dlfs.DLFSProvider;
import convex.dlfs.DLFileSystem;
import convex.dlfs.DLPath;
import convex.restapi.RESTServer;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.InternalServerErrorResponse;
import io.javalin.http.NotFoundResponse;

/**
 * Class for Data Ecosystem services
 * 
 * Designed to conform with the Datacraft DEP standards, see https://github.com/datacraft-dsc/DEPs
 */
public class DLAPI extends ABaseAPI {

	
    private static final String ROUTE = "/dlfs/";

	public DLAPI(RESTServer restServer) {
		super(restServer);
	
	}

	@Override
	public void addRoutes(Javalin app) {
		String prefix=ROUTE;

		app.get(prefix+"<path>", this::getFile);
	}
	
	public void getFile(Context ctx) {
		String pathParam=ctx.pathParam("path");

		
		DLFSProvider provider=DLFS.provider();
		
		Iterator<DLFileSystem> fss=provider.getFileSystems().iterator();
		if (!fss.hasNext()) {
			throw new NotFoundResponse("No DLFS Filesystems available");
		}
		DLFileSystem fs=fss.next();
		DLPath p=fs.getPath(pathParam);
		if (!Files.exists(p) ) {
			throw new NotFoundResponse("Can't find file: "+pathParam);
		}
		
	
		ctx.header("Content-type", "image");
		try {
			ctx.result(Files.newInputStream(p));
		} catch (IOException e) {
			throw new InternalServerErrorResponse("Can't read file");
		}
	}
}
