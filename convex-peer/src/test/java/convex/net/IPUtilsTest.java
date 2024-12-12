package convex.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;

import convex.core.Constants;

import java.net.InetSocketAddress;

public class IPUtilsTest {

	@Test
	public void testInetSocketAddress() {
		String s = "http://www.something-unusual.com:18888";
		InetSocketAddress sa = IPUtils.toInetSocketAddress(s);
		assertNotNull(sa);
		
		assertNotNull(IPUtils.toInetSocketAddress("localhost:8080"));
		
		InetSocketAddress sa1=IPUtils.toInetSocketAddress("12.13.14.15:8080");
		assertNotNull(sa1);
		assertNotNull(IPUtils.toInetSocketAddress("http:12.13.14.15:8080"));
		
		assertNull(IPUtils.toInetSocketAddress("@@@"));

		// No port specified
		assertNull(IPUtils.toInetSocketAddress("peer.convex.live"));

	}
	

	@Test
	public void testIPUtils() {
		InetSocketAddress EXP=new InetSocketAddress("peer.convex.live",Constants.DEFAULT_PEER_PORT);
		
		assertEquals(EXP,IPUtils.parseAddress("peer.convex.live",null));
	}
	
	@Test
	public void testParseAddress() {
		InetSocketAddress EXP=new InetSocketAddress("peer.convex.live",Constants.DEFAULT_PEER_PORT);
		
		assertEquals(EXP,IPUtils.parseAddress("peer.convex.live",null));
		assertEquals(EXP,IPUtils.parseAddress("peer.convex.live",Constants.DEFAULT_PEER_PORT));
		
		InetSocketAddress EXP2=new InetSocketAddress("localhost",8080);
		assertEquals(EXP2,IPUtils.parseAddress("localhost",8080));
		assertEquals(EXP2,IPUtils.parseAddress("localhost:8080",null));
		assertEquals(EXP2,IPUtils.parseAddress("localhost:777",8080));
	}
}
