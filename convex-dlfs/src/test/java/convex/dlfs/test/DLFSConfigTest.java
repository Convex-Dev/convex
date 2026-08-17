package convex.dlfs.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.cvm.Keywords;
import convex.core.data.Keyword;
import convex.dlfs.DLFSConfig;

public class DLFSConfigTest {

	@Test
	public void testSecureDefaults() {
		DLFSConfig config=DLFSConfig.create();
		config.validate();

		assertEquals(DLFSConfig.DEFAULT_NODE_PORT,config.getNodeConfig().getPort());
		assertEquals(DLFSConfig.DEFAULT_HTTP_PORT,config.getHTTPPort());
		assertEquals("127.0.0.1",config.getHTTPBindHost());
		assertEquals(DLFSConfig.DEFAULT_DRIVE,config.getDriveName());
		assertArrayEquals(new Keyword[] {Keywords.FS},config.getRegionPath());
		assertFalse(config.isPublicLatticeInbound());
		assertEquals(DLFSConfig.WRITE_AUTH_NONE,config.getWriteAuthentication());
		assertTrue(config.getBootstrapPeers().isEmpty());
	}

	@Test
	public void testFullJSON5Configuration() {
		AKeyPair nodeKey=AKeyPair.createSeeded(42);
		AKeyPair seedKey=AKeyPair.createSeeded(43);
		String json5="""
			{
			  node: {
			    port: 0,
			    store: 'memory',
			    keypair: '%s',
			    persist: false,
			    bootstrap: [{key: '%s', url: 'tcp://127.0.0.1:18888'}],
			  },
			  dlfs: {
			    path: ['apps', 'documents'],
			    drive: 'work',
			    mounts: [
			      {identity: 'node', owner: 'node'},
			      {identity: 'did:key:zExample', owner: '%s'},
			    ],
			  },
			  http: {bind: 'localhost', port: 0, maxRequestBytes: 4096},
			  security: {latticeInbound: 'public', writeAuthentication: 'node-key'},
			}
			""".formatted(nodeKey.getSeed().toHexString(),
				seedKey.getAccountKey().toHexString(),seedKey.getAccountKey().toHexString());
		DLFSConfig config=DLFSConfig.parse(json5);
		config.validate();

		assertEquals(nodeKey,config.getKeyPair());
		assertEquals("memory",config.getStorePath());
		assertFalse(config.getNodeConfig().isPersist());
		assertArrayEquals(new Keyword[] {Keyword.intern("apps"),Keyword.intern("documents")},
			config.getRegionPath());
		assertEquals("work",config.getDriveName());
		assertEquals(2,config.getDriveMounts().size());
		assertEquals(DLFSConfig.LOCAL_IDENTITY,config.getDriveMounts().get(0).identity());
		assertEquals(seedKey.getAccountKey(),config.getDriveMounts().get(1).owner());
		assertEquals(4096,config.getMaxRequestBytes());
		assertTrue(config.isPublicLatticeInbound());

		var peer=config.getBootstrapPeers().get(0);
		assertEquals(seedKey.getAccountKey(),peer.key());
		assertEquals(InetSocketAddress.createUnresolved("127.0.0.1",18888),peer.address());
	}

	@Test
	public void testPublicUnauthenticatedHTTPRequiresAcknowledgement() {
		DLFSConfig rejected=DLFSConfig.parse("{http:{bind:'0.0.0.0'}}");
		assertThrows(IllegalArgumentException.class,rejected::validate);

		DLFSConfig accepted=DLFSConfig.parse("""
			{http:{bind:'0.0.0.0'},security:{allowUnauthenticatedHTTP:true}}
			""");
		accepted.validate();
	}

	@Test
	public void testInvalidBootstrapAndPoliciesRejected() {
		assertThrows(IllegalArgumentException.class,()->DLFSConfig.parse(
			"{node:{bootstrap:[{key:'bad',url:'tcp://localhost:18888'}]}}").validate());
		assertThrows(IllegalArgumentException.class,()->DLFSConfig.parse(
			"{node:{bootstrap:[{key:'"+AKeyPair.createSeeded(1).getAccountKey().toHexString()
			+"',url:'https://localhost:18888'}]}}").validate());
		assertThrows(IllegalArgumentException.class,()->DLFSConfig.parse(
			"{security:{latticeInbound:'private'}}").validate());
		assertThrows(IllegalArgumentException.class,()->DLFSConfig.parse(
			"{security:{writeAuthentication:'optional'}}").validate());
		assertThrows(IllegalArgumentException.class,()->DLFSConfig.parse(
			"{security:{allowUnauthenticatedHTTP:'yes'}}").validate());
		assertThrows(IllegalArgumentException.class,()->DLFSConfig.parse(
			"{node:{port:4294967296}}").validate());
		assertThrows(IllegalArgumentException.class,()->DLFSConfig.parse(
			"{http:{port:4294967296}}").validate());
		assertThrows(IllegalArgumentException.class,()->DLFSConfig.parse(
			"{dlfs:{mounts:[{identity:'user',owner:'bad'}]}}").validate());
		assertThrows(IllegalArgumentException.class,()->DLFSConfig.parse("[]"));
	}

	@Test
	public void testEncryptedEtchRequiresPersistentIdentity() {
		String etch="{version:3,cipher:'aes-256-ctr',encryptIndex:true}";
		assertThrows(IllegalArgumentException.class,()->DLFSConfig.parse(
			"{node:{store:'secure.etch',etch:"+etch+"}}").validate());
		assertThrows(IllegalArgumentException.class,()->DLFSConfig.parse(
			"{node:{store:'memory',etch:{version:3}}}").validate());

		AKeyPair key=AKeyPair.createSeeded(99);
		DLFSConfig secured=DLFSConfig.parse("{node:{store:'secure.etch',keypair:'"
			+key.getSeed().toHexString()+"',etch:"+etch+"}}");
		secured.validate();
		assertEquals(key,secured.getKeyPair());
	}

	@Test
	public void testOverridesRemainImmutable() {
		DLFSConfig original=DLFSConfig.create();
		DLFSConfig changed=original.withHTTPPort(9000).withNodePort(19000)
			.withDriveName("documents").withPublicLatticeInbound();

		assertEquals(DLFSConfig.DEFAULT_HTTP_PORT,original.getHTTPPort());
		assertFalse(original.isPublicLatticeInbound());
		assertEquals(9000,changed.getHTTPPort());
		assertEquals(19000,changed.getNodeConfig().getPort());
		assertEquals("documents",changed.getDriveName());
		assertTrue(changed.isPublicLatticeInbound());
	}

	@Test
	public void testPackagedExample() throws Exception {
		try (InputStream in=getClass().getResourceAsStream("/convex/dlfs/config-example.json5")) {
			if (in==null) throw new AssertionError("Packaged DLFS config example is missing");
			DLFSConfig config=DLFSConfig.parse(
				new String(in.readAllBytes(),StandardCharsets.UTF_8));
			config.validate();
			assertEquals(DLFSConfig.WRITE_AUTH_NODE_KEY,config.getWriteAuthentication());
		}
	}
}
