package convex.p2p;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.Root;

/** Which nodes and addresses a blocklist refuses, however an address is written. */
public class BlocklistTest {

	private final Blocklist blocklist=new Blocklist(new Root<>(),() -> CVMLong.create(100));

	private static AString s(String text) {
		return Strings.create(text);
	}

	@Test void addressesAreStoredInOneForm() {
		assertEquals(s("tcp://peer.example:18888"),Blocklist.address(s("TCP://Peer.Example")));
		assertEquals(s("tcp://peer.example:18888"),Blocklist.address(s("tcp://peer.example:18888")));
		assertEquals(s("tcp://[0:0:0:0:0:0:0:1]:9000"),Blocklist.address(s("tcp://[::1]:9000")));
		assertEquals(s("0:0:0:0:0:0:0:1"),Blocklist.address(s("[::1]")));
		assertEquals(s("0:0:0:0:0:0:0:1"),Blocklist.address(s("0:0::1")));
		assertEquals(s("10.0.0.1"),Blocklist.address(s("::ffff:10.0.0.1")),"IPv4 written in IPv6");
		assertEquals(s("peer.example"),Blocklist.address(s("PEER.example")));

		assertTrue(blocklist.block(s("TCP://Peer.Example:18888")));
		assertFalse(blocklist.block(s("tcp://peer.example")),"the same address");
		assertTrue(blocklist.isBlocked(s("tcp://PEER.example:18888")));
	}

	@Test void anythingButATransportAddressOrHostIsRefused() {
		for (String bad:new String[] {"","tcp://peer.example/path","tcp://user@peer.example:1",
				"tcp://peer.example:1?query","tcp://peer.example:1#part","peer.example:1","bad host",
				"10.0.0.1:1","1::2::3"}) {
			assertThrows(IllegalArgumentException.class,() -> blocklist.block(s(bad)),bad);
		}
		assertThrows(IllegalArgumentException.class,() -> blocklist.block((AString)null));
	}

	@Test void aNodeKeyBlocksThatNodeOnly() {
		var bob=AKeyPair.createSeeded(1).getAccountKey();
		var carol=AKeyPair.createSeeded(2).getAccountKey();
		assertTrue(blocklist.block(bob));
		assertTrue(blocklist.isBlocked(bob));
		assertFalse(blocklist.isBlocked(carol));
		assertFalse(blocklist.isBlocked((AccountKey)null));
		assertTrue(blocklist.unblock(bob));
		assertFalse(blocklist.isBlocked(bob));
	}

	@Test void anIPAddressBlocksDiallingItAndConnectionsFromIt() {
		blocklist.block(s("10.0.0.1"));
		assertTrue(blocklist.refuses(s("tcp://10.0.0.1:5")));
		assertTrue(blocklist.refuses(s("tls://10.0.0.1")));
		assertTrue(blocklist.refuses(new InetSocketAddress("10.0.0.1",40000)));
		assertFalse(blocklist.refuses(s("tcp://10.0.0.2:5")));
		assertFalse(blocklist.refuses(new InetSocketAddress("10.0.0.2",40000)));
		assertFalse(blocklist.isBlocked(s("tcp://10.0.0.1:5")),"refused, but not itself on the list");
	}

	@Test void aTransportAddressBlocksOnlyThatAddress() {
		blocklist.block(s("tcp://10.0.0.1:5"));
		assertTrue(blocklist.refuses(s("tcp://10.0.0.1:5")));
		assertTrue(blocklist.refuses(s("10.0.0.1:5")),"a transport without a scheme is TCP");
		assertFalse(blocklist.refuses(s("tcp://10.0.0.1:6")));
		assertFalse(blocklist.refuses(s("tls://10.0.0.1:5")));
		assertFalse(blocklist.refuses(new InetSocketAddress("10.0.0.1",5)),"an inbound port is not an address");
	}

	@Test void aHostNameBlocksDiallingButNotConnections() {
		blocklist.block(s("peer.example"));
		assertTrue(blocklist.refuses(s("tcp://PEER.example:1")));
		assertFalse(blocklist.refuses(s("tcp://other.example:1")));
		assertFalse(blocklist.refuses(new InetSocketAddress("10.0.0.1",1)));
		assertFalse(blocklist.refuses(InetSocketAddress.createUnresolved("peer.example",1)),"no DNS lookup");
	}

	@Test void dialableTransportsLeaveOutRefusedOnes() {
		AVector<AString> transports=Vectors.of(s("tcp://peer.example:1"),s("tcp://10.0.0.1:2"),s("not a uri"));
		assertSame(transports,blocklist.dialable(transports),"nothing refused");
		blocklist.block(s("peer.example"));
		assertEquals(Vectors.of(s("tcp://10.0.0.1:2"),s("not a uri")),blocklist.dialable(transports));
		blocklist.block(s("tcp://10.0.0.1:2"));
		assertEquals(Vectors.of(s("not a uri")),blocklist.dialable(transports),"left to the dialler, which skips it");
	}
}
