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
	public void testTcpURLFormat() {
		// tcp:// URLs — the canonical format for peer addresses
		InetSocketAddress sa1 = IPUtils.toInetSocketAddress("tcp://localhost:18888");
		assertNotNull(sa1);
		assertEquals("localhost", sa1.getHostName());
		assertEquals(18888, sa1.getPort());

		// tcp:// with explicit port
		InetSocketAddress sa2 = IPUtils.toInetSocketAddress("tcp://peer.convex.live:9999");
		assertNotNull(sa2);
		assertEquals("peer.convex.live", sa2.getHostName());
		assertEquals(9999, sa2.getPort());

		// tcp:// without port — should use default peer port
		InetSocketAddress sa3 = IPUtils.toInetSocketAddress("tcp://peer.convex.live");
		assertNotNull(sa3);
		assertEquals("peer.convex.live", sa3.getHostName());
		assertEquals(Constants.DEFAULT_PEER_PORT, sa3.getPort());

		// Legacy bare host:port — must still work
		InetSocketAddress sa4 = IPUtils.toInetSocketAddress("localhost:18888");
		assertNotNull(sa4);
		assertEquals("localhost", sa4.getHostName());
		assertEquals(18888, sa4.getPort());

		// http:// scheme — should also work
		InetSocketAddress sa5 = IPUtils.toInetSocketAddress("http://example.com:8080");
		assertNotNull(sa5);
		assertEquals("example.com", sa5.getHostName());
		assertEquals(8080, sa5.getPort());
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
