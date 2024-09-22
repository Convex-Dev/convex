package convex.restapi.test;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.URI;
import java.net.URISyntaxException;

import convex.core.Result;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.ResultException;
import convex.core.init.Init;
import convex.core.lang.Reader;
import convex.core.util.Utils;
import convex.java.ConvexHTTP;

public class ConvexHTTPTest extends ARESTTest {

	private ConvexHTTP connect() {
		try {
			URI uri=new URI(HOST_PATH);
			// System.out.println("Connect to: "+uri);
			return ConvexHTTP.connect(uri,Init.GENESIS_ADDRESS,KP);
		} catch (URISyntaxException e) {
			throw Utils.sneakyThrow(e);
		}
	}
	
	@Test public void testQuery() throws ResultException, InterruptedException {
		ConvexHTTP convex=connect();
		Long l=convex.getBalance();
		assertNotNull(l);
		
		Result r=convex.querySync(Reader.read("(+ 2 3)"), null);
		assertFalse(r.isError());
		assertEquals(CVMLong.create(5),r.getValue());
	}
}
