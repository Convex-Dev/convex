package convex.restapi.test;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URISyntaxException;

import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.SourceCodes;
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
	
	@Test public void testTransact() throws ResultException, InterruptedException {
		ConvexHTTP convex=connect();
		convex.setAddress(Init.GENESIS_ADDRESS);
		convex.setKeyPair(KP);
		
		Result r=convex.transactSync("(+ 2 3)");
		assertFalse(r.isError());
		assertEquals(CVMLong.create(5),r.getValue());
		
		r=convex.transactSync("(+ :foo 3)");
		assertEquals(ErrorCodes.CAST,r.getErrorCode());
		assertEquals(SourceCodes.CODE,r.getSource()); // should fail in user code
	}
	
	@Test public void testBadHost() throws ResultException, InterruptedException, URISyntaxException {
		ConvexHTTP convex= ConvexHTTP.connect(new URI("http://localhost:1"),Init.GENESIS_ADDRESS,KP);
		assertEquals(1,convex.getHostAddress().getPort());
		convex.setAddress(Init.GENESIS_ADDRESS);
		convex.setKeyPair(KP);
		
		Result r=convex.transactSync("(+ 2 3)");
		assertTrue(r.isError());
		assertEquals(ErrorCodes.IO,r.getErrorCode());
		assertEquals(SourceCodes.NET,r.getSource());
	}
}
