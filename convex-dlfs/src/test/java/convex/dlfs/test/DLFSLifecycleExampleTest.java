package convex.dlfs.test;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.auth.did.DID;
import convex.core.crypto.AKeyPair;
import convex.core.data.Keyword;
import convex.core.data.prim.CVMLong;
import convex.dlfs.DLFSApplication;
import convex.dlfs.DLFSDriveManager;
import convex.dlfs.DLFSServer;
import convex.etch.Etch;
import convex.etch.EtchStore;
import convex.lattice.LatticeHost;
import convex.lattice.fs.DLFSLattice;

/** Complete local and HTTP assemblies, including the request's durability promise. */
class DLFSLifecycleExampleTest {
	private static final AKeyPair OWNER=AKeyPair.createSeeded(824);
	private static final Keyword APP=Keyword.intern("app");
	private static final Keyword EXAMPLE=Keyword.intern("lifecycle-example");
	private static final Keyword DOCUMENTS=Keyword.intern("documents");

	@Test void customPathAndDidOwnershipReopenWithoutPrivateKeys() throws Exception {
		var owner=DID.forKey(OWNER.getAccountKey());
		File file;
		// The host closes first: its final checkpoint, before the store closes.
		try (var store=EtchStore.createTemp("dlfs-did-lifecycle"); var host=LatticeHost.open(store)) {
			file=store.getFile();
			var files=host.addApplication(new DLFSApplication(host,
				(subject,key) -> owner.equals(subject) && OWNER.getAccountKey().equals(key),
				(subject,value) -> owner.equals(subject)?OWNER.signData(value):null,
				() -> CVMLong.ONE,DLFSLattice.DEFAULT_MAX_FUTURE_TIMESTAMP_SKEW,APP,EXAMPLE,DOCUMENTS));
			var drive=files.drives(owner).getOrCreateDrive("home");
			assertNotNull(drive);
			Files.writeString(drive.fileSystem().getPath("/hello.txt"),"Hello");
			files.sync();
		}
		try (var store=EtchStore.create(file); var host=LatticeHost.open(store)) {
			// Reopening a reader requires verification policy but no private signer.
			var files=host.addApplication(new DLFSApplication(host,
				(subject,key) -> owner.equals(subject) && OWNER.getAccountKey().equals(key),
				null,() -> CVMLong.ONE,DLFSLattice.DEFAULT_MAX_FUTURE_TIMESTAMP_SKEW,APP,EXAMPLE,DOCUMENTS));
			assertEquals("Hello",Files.readString(files.drives(owner).drive("home")
				.fileSystem().getPath("/hello.txt")));
		}
	}

	@Test void webdavDurableCompletionFailsWhenFlushFailsAndCanBeRetried() throws Exception {
		var flushes=new AtomicInteger();
		var expected=new IOException("disk unavailable");
		var failures=new LinkedBlockingQueue<Exception>();
		try (var store=new EtchStore(Etch.createTempEtch("dlfs-durable-http")) {
			@Override public void flush() throws IOException {
				if (flushes.getAndIncrement()==0) throw expected;
				super.flush();
			}
		}; var client=HttpClient.newHttpClient(); var host=LatticeHost.open(store)) {
			var app=host.addApplication(new DLFSApplication(host,OWNER::signData));
			var drives=app.drives(OWNER.getAccountKey());
			assertNotNull(drives.getOrCreateDrive("home"));
			var routes=new DLFSDriveManager(drives,() -> {
				app.sync();
				try { host.checkpoint(); }
				catch (IOException e) { throw new UncheckedIOException(e); }
			});
			try (var server=DLFSServer.create(routes).onFailure(failures::add)) {
				server.start(0);
				var request=HttpRequest.newBuilder(URI.create("http://localhost:"+server.getPort()+"/dlfs/home/hello.txt"))
					.PUT(HttpRequest.BodyPublishers.ofString("Hello")).build();
				assertEquals(500,client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
				// The failed barrier did not roll back the file. Repeating this PUT is safe.
				assertEquals("Hello",Files.readString(drives.drive("home").fileSystem().getPath("/hello.txt")));
				int status=client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode();
				assertTrue(status>=200 && status<300);
				assertEquals(2,flushes.get());
				var failure=assertInstanceOf(UncheckedIOException.class,failures.poll(5,TimeUnit.SECONDS));
				assertSame(expected,failure.getCause());
				server.close();
				assertTrue(failures.isEmpty(),"No additional request failures");
			}
			try (var restored=LatticeHost.open(store)) {
				var reader=restored.addApplication(new DLFSApplication(restored));
				assertEquals("Hello",Files.readString(reader.drives(OWNER.getAccountKey()).drive("home")
					.fileSystem().getPath("/hello.txt")));
			}
		}
	}
}
