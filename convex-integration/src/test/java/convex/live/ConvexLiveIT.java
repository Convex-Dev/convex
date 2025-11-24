package convex.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.net.URISyntaxException;

import org.junit.jupiter.api.Test;

import convex.core.Result;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.Keywords;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Keyword;
import convex.core.util.JSON;
import convex.core.util.Utils;
import convex.java.ConvexHTTP;

@SuppressWarnings("unused")
public class ConvexLiveIT {

	AKeyPair kp=AKeyPair.createSeeded(5657558);
	AccountKey key=kp.getAccountKey();
	Blob seed = kp.getSeed();
	
	@Test public void testProtonet() throws URISyntaxException, InterruptedException {
		ConvexHTTP convex=ConvexHTTP.connect(new URI("https://peer.convex.live"));
		
		// Smoke test query
		assertEquals(Address.ZERO,convex.querySync("#0").getValue());
		
		// Create-account should fail here (invalid address)
		assertThrows(IllegalArgumentException.class,()->convex.createAccount(key));
		
	}
	
	@Test 
	public void testProtonetStatus() throws URISyntaxException, InterruptedException {
		ConvexHTTP convex=ConvexHTTP.connect(new URI("https://peer.convex.live"));
		
		// TODO: fix status endpoint
		Result r = convex.requestStatusSync();
		//System.out.println(r+ " : "+Utils.getClassName(r.getValue()));
		//AMap<Keyword,ACell> status=JSON.parse(r.getValue());
		//assertEquals("0xb0e44f2a645abfa539f5b96b7a0eabb0f902866feaff0f7c12d1213e02333f13",status.get(Keywords.GENESIS));
		
	}
}
