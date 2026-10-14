package convex.dlfs.test;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Ref;
import convex.core.data.SignedData;
import convex.core.store.MemoryStore;
import convex.dlfs.DLFSApplication;
import convex.dlfs.DLFSDrive;
import convex.dlfs.DLFSRegion;
import convex.lattice.CheckpointPolicy;
import convex.lattice.LatticeContext;
import convex.lattice.RootComponent;
import convex.lattice.fs.DLFileSystem;

public class CheckpointPolicyTest {
	private static final class CountingStore extends MemoryStore {
		int writes;
		@Override public <T extends ACell> Ref<T> setRootData(T value) {
			writes++;
			return super.setRootData(value);
		}
		@Override public <T extends ACell> Ref<T> storeTopRef(Ref<T> ref,int status,
				Consumer<Ref<ACell>> noveltyHandler) {
			writes++;
			return super.storeTopRef(ref,status,noveltyHandler);
		}
	}

	@Test public void testConstructionResolvesPolicyWithoutRetainingSource() throws Exception {
		var key=AKeyPair.createSeeded(7309);
		try (var store=new CountingStore()) {
			var host=RootComponent.create(DLFSRegion.LATTICE,store);
			host.cursor().setContext(LatticeContext.create(null,key));
			var app=DLFSApplication.connect(host);
			var otherApp=DLFSApplication.connect(host);
			var owners=app.drives(key.getAccountKey());
			var drive=owners.createDrive("home");
			var sibling=owners.createDrive("archive");
			assertEquals(CheckpointPolicy.INCREMENTAL,drive.getCheckpointPolicy());
			app.setCheckpointPolicy(CheckpointPolicy.DEFERRED);
			assertEquals(CheckpointPolicy.INCREMENTAL,drive.getCheckpointPolicy());
			assertEquals(CheckpointPolicy.INCREMENTAL,otherApp.getCheckpointPolicy());
			var newView=app.drives(key.getAccountKey()).drive("home");
			assertEquals(CheckpointPolicy.DEFERRED,newView.getCheckpointPolicy());
			assertEquals(drive.cursor().get(),newView.cursor().get());
			drive.setCheckpointPolicy(CheckpointPolicy.DEFERRED);
			assertEquals(CheckpointPolicy.INCREMENTAL,sibling.getCheckpointPolicy());

			var fork=drive.fork();
			assertEquals(CheckpointPolicy.DEFERRED,fork.getCheckpointPolicy());
			fork.transact(view -> assertEquals(CheckpointPolicy.DEFERRED,view.getCheckpointPolicy()));
			drive.setCheckpointPolicy(CheckpointPolicy.INCREMENTAL);
			assertEquals(CheckpointPolicy.DEFERRED,fork.getCheckpointPolicy());
			fork.setCheckpointPolicy(drive.getCheckpointPolicy());
			assertEquals(CheckpointPolicy.INCREMENTAL,fork.getCheckpointPolicy());
			assertThrows(NullPointerException.class,() -> fork.setCheckpointPolicy(null));
		}
	}

	@Test public void testDeferredWritesSignOnMergeBackAndPersistOnPublication() throws Exception {
		var key=AKeyPair.createSeeded(7310);
		AtomicInteger signatures=new AtomicInteger();
		try (var store=new CountingStore()) {
			var host=RootComponent.create(DLFSRegion.LATTICE,store);
			host.cursor().setContext(new LatticeContext() {
				@Override public AKeyPair getSigningKey() { return key; }
				@Override public <T extends ACell> SignedData<T> sign(AccountKey owner,T value) {
					signatures.incrementAndGet();
					return super.sign(owner,value);
				}
			});
			var app=DLFSApplication.connect(host);
			app.setCheckpointPolicy(CheckpointPolicy.DEFERRED);
			var drive=app.drives(key.getAccountKey()).createDrive("home");
			app.sync();
			var published=store.getRootData();
			store.writes=0;
			signatures.set(0);
			byte[] bytes=new byte[DLFileSystem.BLOB_PERSIST_INTERVAL];
			bytes[0]=42;
			try (var fork=drive.fork()) {
				Files.write(fork.fileSystem().getPath("/deferred.bin"),bytes);
				assertEquals(0,store.writes);
				assertEquals(0,signatures.get());
				assertFalse(Files.exists(drive.fileSystem().getPath("/deferred.bin")));
				fork.sync();
				assertEquals(1,signatures.get());
				assertEquals(0,store.writes);
				assertEquals(published,store.getRootData());
			}
			app.sync();
			assertTrue(store.writes>0);
			assertEquals(host.cursor().get(),store.getRootData());
			assertArrayEquals(bytes,Files.readAllBytes(drive.fileSystem().getPath("/deferred.bin")));

			// An explicit request still persists under deferred policy.
			store.writes=0;
			drive.persist();
			assertTrue(store.writes>0);

			try (var fork=drive.fork()) {
				fork.setCheckpointPolicy(CheckpointPolicy.INCREMENTAL);
				store.writes=0;
				signatures.set(0);
				Files.write(fork.fileSystem().getPath("/checkpoint.bin"),bytes);
				assertTrue(store.writes>0);
				assertEquals(0,signatures.get());
				assertFalse(Files.exists(drive.fileSystem().getPath("/checkpoint.bin")));
				assertEquals(host.cursor().get(),store.getRootData());
			}
		}
	}

	@Test public void testDeferredTransactionRollbackDoesNotWriteStore() throws Exception {
		var key=AKeyPair.createSeeded(7311);
		try (var store=new CountingStore()) {
			var app=DLFSApplication.open(store,key);
			var drive=app.drives(key.getAccountKey()).createDrive("home");
			drive.setCheckpointPolicy(CheckpointPolicy.DEFERRED);
			var before=drive.cursor().get();
			var rollback=new IllegalStateException("discard working value");
			assertSame(rollback,assertThrows(IllegalStateException.class,() -> drive.transact(view -> {
				writeLargeFile(view);
				throw rollback;
			})));
			assertSame(before,drive.cursor().get());
			assertEquals(0,store.writes);
		}
	}

	private static void writeLargeFile(DLFSDrive drive) {
		try {
			Files.write(drive.fileSystem().getPath("/transaction.bin"),new byte[DLFileSystem.BLOB_PERSIST_INTERVAL]);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
