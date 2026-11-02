package convex.cli.dlfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.api.Test;

import convex.cli.CLTester;
import convex.cli.ExitCodes;
import convex.core.crypto.AKeyPair;
import convex.core.data.AccountKey;
import convex.core.store.MemoryStore;
import convex.dlfs.DLFSApplication;
import convex.dlfs.DLFSServer;
import convex.lattice.LatticeHost;
import convex.net.PeerName;

public class DlfsStartTest {

	@Test
	public void testServiceMutationsPublishBeforeShutdown() throws Exception {
		var key=AKeyPair.createSeeded(9241);
		try (var store=new MemoryStore()) {
			var host=LatticeHost.create(store);
			var application=host.addApplication(new DLFSApplication(host,key::signData));
			var drives=DlfsStart.createDriveManager(host,application,key.getAccountKey());
			assertTrue(drives.createDrive(null,"home"));
			drives.sync();
			assertEquals(host.cursor().get(),store.getRootData(),"initial drives must be retained");
			try (var server=DLFSServer.create(drives)) {
				server.start(0);
				var client=HttpClient.newHttpClient();
				var base=URI.create("http://127.0.0.1:"+server.getPort()+"/dlfs/");
				var put=HttpRequest.newBuilder(base.resolve("home/file"))
					.PUT(HttpRequest.BodyPublishers.ofString("published before shutdown")).build();
				assertEquals(201,client.send(put,HttpResponse.BodyHandlers.ofString()).statusCode());
				assertEquals(host.cursor().get(),store.getRootData(),"file completion must publish the host");
				var restored=new DLFSApplication(LatticeHost.open(store));
				assertEquals("published before shutdown",java.nio.file.Files.readString(
					restored.drives(key.getAccountKey()).drive("home").fileSystem().getPath("/file")));

				var create=HttpRequest.newBuilder(base.resolve("extra/"))
					.method("MKCOL",HttpRequest.BodyPublishers.noBody()).build();
				assertEquals(201,client.send(create,HttpResponse.BodyHandlers.ofString()).statusCode());
				assertEquals(host.cursor().get(),store.getRootData(),"registry completion must publish the host");
				var delete=HttpRequest.newBuilder(base.resolve("extra/")).DELETE().build();
				assertEquals(204,client.send(delete,HttpResponse.BodyHandlers.ofString()).statusCode());
				assertEquals(host.cursor().get(),store.getRootData());
			}
		}
	}

	@Test
	public void testPersistentStoreRequiresStableKey() {
		CLTester result=CLTester.run("dlfs", "start", "--etch", "unused.etch");

		assertEquals(ExitCodes.CONFIG, result.getResult());
		assertTrue(result.getError().contains("requires a stable key"));
	}

	/** A malformed or unsupported peer name stops startup before anything opens. */
	@Test
	public void testInvalidPeerRejectedBeforeStartup() {
		for (String peer:new String[] {"did:convex:13","did:web:example.com/path",
				"localhost","tcp://localhost","https://localhost:443","nothex@localhost:19889"}) {
			CLTester result=CLTester.run("dlfs", "start", "--no-tray", "--peer", peer);
			assertEquals(ExitCodes.USAGE, result.getResult(), peer);
		}
	}

	/** host:port is a TCP peer, a URI keeps its transport, and a DID or expected key is kept. */
	@Test
	public void testPeersAreNames() {
		AccountKey key=AKeyPair.createSeeded(9242).getAccountKey();
		assertEquals(List.of(new PeerName(URI.create("tcp://localhost:19889"),null),
				new PeerName(URI.create("tls://peer.example.com:443"),null),
				new PeerName(URI.create("did:web:files.example.com"),key)),
			DlfsStart.peerNames(new String[] {"localhost:19889","tls://peer.example.com:443",
				key.toHexString()+"@did:web:files.example.com"}));
		assertEquals(List.of(),DlfsStart.peerNames(null));
	}

	@Test
	public void testInvalidDriveRejectedBeforeStartup() {
		CLTester result=CLTester.run("dlfs", "start", "--drive", "../escape");

		assertEquals(ExitCodes.CONFIG, result.getResult());
		assertTrue(result.getError().contains("Invalid drive name"));
	}
}
