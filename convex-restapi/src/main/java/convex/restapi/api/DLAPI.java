package convex.restapi.api;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Iterator;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.lang.RT;
import convex.dlfs.DLFS;
import convex.dlfs.DLFSNode;
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
		System.err.println("DLFS Request: "+pathParam);
		
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
		
	
		try {
			if (Files.isDirectory(p)) {
				ctx.header("Content-type", "text/plain");
				AVector<ACell> dir=fs.getNode(p);
				AHashMap<AString, AVector<ACell>> ents = DLFSNode.getDirectoryEntries(dir);
				ACell[] names=ents.getKeys().toCellArray();
				StringBuilder sb=new StringBuilder();
				for (ACell a:names) {
					sb.append(RT.str(a));
				}
				if (!sb.isEmpty()) {
					ctx.result("Empty DLFS Directory");
				} else {
					ctx.result(sb.toString());
				}
			} else {
				ctx.header("Content-type", "image");
				ctx.result(Files.newInputStream(p));
			}
			
		} catch (IOException e) {
			throw new InternalServerErrorResponse("Can't read file");
		}
	}
}
