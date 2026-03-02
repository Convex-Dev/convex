package convex.restapi.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URISyntaxException;

import org.junit.jupiter.api.Test;

import convex.core.ErrorCodes;
import convex.core.Result;
import convex.core.SourceCodes;
import convex.core.cvm.Keywords;
import convex.core.data.Hash;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.ResultException;
import convex.core.init.Init;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.util.Utils;
import convex.java.ConvexHTTP;

public class ConvexHTTPTest extends ARESTTest {


	
	@Test public void testQuery() throws ResultException, InterruptedException {
		ConvexHTTP convex=connect();
		Long l=convex.getBalance();
		assertNotNull(l);
		
		Result r=convex.querySync(Reader.read("(+ 2 3)"), null);
		assertFalse(r.isError());
		assertEquals(CVMLong.create(5),r.getValue());
	}
	
	@Test public void testPreCalculate() throws ResultException, InterruptedException {
		ConvexHTTP convex=newClient();
		
		Result r=convex.querySync("(+ 2 3)");
		assertFalse(r.isError());
		AInteger fees=r.getIn(Keywords.INFO,Keywords.FEES);
		assertNotNull(fees);
		
		Result r2=convex.transactSync("(+ 2 3)");
		assertNull(r2.getErrorCode());
		assertEquals(fees,r2.getIn(Keywords.INFO,Keywords.FEES));
	}

	@Test public void testOffensiveMessages() throws ResultException, InterruptedException {
		ConvexHTTP convex=connect();
		convex.setAddress(Init.GENESIS_ADDRESS);
		convex.setKeyPair(KP);
		
		// TODO: blast with illegal messages
		// CompletableFuture<Result> r=convex.message(Blob.fromHex("0xBADBADBADBAD"));
	}
	
	@Test public void testTransact() throws ResultException, InterruptedException {
		ConvexHTTP convex=connect();
		convex.setAddress(Init.GENESIS_ADDRESS);
		convex.setKeyPair(KP);
		
		Result r=convex.transactSync("(+ 2 3)");
		assertFalse(r.isError());
		assertEquals(CVMLong.create(5),r.getValue());
		
		// Should be a transaction hash
		Hash tx=RT.ensureHash(RT.getIn(r,Keywords.INFO,Keywords.TX));
		assertNotNull(tx);
		
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
	
	@Test public void testRequestStatus() throws ResultException, InterruptedException {
		ConvexHTTP convex=connect();
		
		Result r=convex.requestStatusSync();
		assertFalse(r.isError(), ()->"Error in status request: " + r);
		assertNotNull(r.getValue(), "Status result should have a value");
		assertTrue(r.getValue() instanceof convex.core.data.AMap, 
			"Status result should be a map but got: " + Utils.getClassName(r.getValue()));
		
		// Check that the status map contains expected keys
		@SuppressWarnings("unchecked")
		convex.core.data.AMap<convex.core.data.Keyword, convex.core.data.ACell> statusMap = 
			(convex.core.data.AMap<convex.core.data.Keyword, convex.core.data.ACell>) r.getValue();
		
		// Verify common status fields exist
		assertNotNull(statusMap.get(Keywords.BELIEF), "Status should contain belief hash");
		assertNotNull(statusMap.get(Keywords.PEER), "Status should contain peer key");
	}
}
