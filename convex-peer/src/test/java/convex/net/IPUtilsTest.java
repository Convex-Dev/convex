package convex.net;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;

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

	}
}
