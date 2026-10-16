package convex.lattice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.Blobs;
import convex.core.data.Keyword;
import convex.core.data.RefSoft;
import convex.core.data.Sets;
import convex.core.data.prim.CVMLong;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.core.store.NullStore;
import convex.etch.EtchStore;
import convex.lattice.generic.SetLattice;

/** Host persistence contracts for absent, discarding, volatile and durable stores. */
public class HostStoresTest {
	@Test
	public void testStorelessHostNeedsNoPersistenceResources() throws Exception {
		var host=LatticeHost.create();
		assertNull(host.store());
		assertTrue(host.cursor().get().isEmpty());
		var value=host.cursor().get().assoc(Keyword.intern("items"),Sets.of(1));
		host.cursor().set(value);
		assertSame(value,host.sync());
		assertSame(value,host.persist());
		assertSame(value,host.persist(value));
		assertNull(host.persist(null));
		host.flush();
		assertSame(value,host.cursor().get());
		assertNull(LatticeHost.create(null).store());
		assertTrue(LatticeHost.open(null).cursor().get().isEmpty());
		assertSame(value,LatticeHost.open(value,null).cursor().get());
		assertNull(LatticeHost.create((ACell)null,null).sync());
	}

	@Test
	public void testStorelessHostStillRunsPublicationPolicy() throws Exception {
		var host=LatticeHost.create(CVMLong.ONE,null);
		var published=new AtomicReference<CVMLong>();
		host.setPublicationPolicy(value -> {
			published.set(value);
			return value;
		});
		assertSame(CVMLong.ONE,host.sync());
		assertSame(CVMLong.ONE,published.get());
	}


	private static final class SetApplication extends ALatticeApplication<ASet<ACell>> {
		SetApplication(LatticeHost<ASet<ACell>> host) {
			super(SetLattice.create(),host);
		}
	}

	private static final class FlushStore extends MemoryStore {
		private boolean flushed;

		@Override
		public void flush() {
			flushed=true;
		}
	}

	@Test
	public void testLocalStorePersistenceDoesNotMoveCursor() throws Exception {
		try (EtchStore store=EtchStore.createTemp("root-component")) {
			LatticeHost<ASet<ACell>> root=LatticeHost.create(SetLattice.create().zero(),store);
			ABlob blob=Blobs.createRandom(new Random(1234),5000);
			root.cursor().updateAndGet(values->values.include(blob));
			ASet<ACell> before=root.cursor().get();

			ASet<ACell> persisted=root.persist();

			assertSame(before,root.cursor().get(),"store persistence must not move the cursor");
			assertEquals(before,persisted);
			ABlob persistedBlob=(ABlob)persisted.getRef(0).getValue();
			assertInstanceOf(RefSoft.class,persistedBlob.getRef(0));
			assertSame(store,root.store());
		}
	}

	@Test
	public void testFlushDelegatesToStore() throws Exception {
		try (FlushStore store=new FlushStore()) {
			LatticeHost<ASet<ACell>> root=LatticeHost.create(SetLattice.create().zero(),store);

			root.flush();

			assertTrue(store.flushed);
		}
	}

	@Test
	public void testLocalSyncSelectsStoredRoot() throws Exception {
		try (MemoryStore store=new MemoryStore()) {
			LatticeHost<ASet<ACell>> root=LatticeHost.create(SetLattice.create().zero(),store);
			ABlob blob=Blobs.createRandom(new Random(5678),5000);
			root.cursor().updateAndGet(values->values.include(blob));

			assertNull(store.getRootData(),"Writing to the host cursor must not publish");
			root.sync();

			assertEquals(root.cursor().get(),store.getRootData());
		}
	}

	@Test
	public void testApplicationPublishesWorkingStateBeforeHostPersistence() throws Exception {
		try (MemoryStore store=new MemoryStore()) {
			LatticeHost<ASet<ACell>> root=LatticeHost.create(SetLattice.create().zero(),store);
			SetApplication application=new SetApplication(root);
			ABlob blob=Blobs.createRandom(new Random(9012),5000);

			assertTrue(application.getLattice() instanceof SetLattice);
			application.cursor().updateAndGet(values->values.include(blob));
			assertTrue(root.cursor().get().isEmpty());
			application.sync();
			assertEquals(application.cursor().get(),root.cursor().get());
			assertNull(store.getRootData());
			root.sync();

			assertEquals(application.cursor().get(),store.getRootData());
		}
	}

	private enum StoreType {
		MEMORY, ETCH;

		AStore open(Path file) throws IOException {
			return this==MEMORY?new MemoryStore():EtchStore.create(file.toFile());
		}
	}

	@ParameterizedTest
	@EnumSource(StoreType.class)
	void createOpenPersistAndSyncHaveDistinctEffects(StoreType type,@TempDir Path directory) throws Exception {
		try (var store=type.open(directory.resolve("host.etch"))) {
			var initial=Sets.<ACell>of(CVMLong.ONE);
			var retained=Sets.<ACell>of(CVMLong.TWO);
			assertEquals(initial,LatticeHost.open(initial,store).cursor().get());
			assertNull(store.getRootData(),"Opening an empty store does not write a root");
			store.setRootData(retained);
			var restored=LatticeHost.open(initial,store);
			assertSame(store,restored.store());
			assertEquals(retained,restored.cursor().get(),"Open restores instead of using the fallback");

			var fresh=LatticeHost.create(initial,store);
			assertEquals(initial,fresh.cursor().get());
			assertEquals(retained,store.getRootData(),"Create does not overwrite until sync");
			var blob=Blobs.createRandom(new Random(7310),5000);
			var updated=initial.include(blob);
			fresh.cursor().set(updated);
			assertEquals(updated,fresh.persist());
			assertSame(updated,fresh.cursor().get(),"Persist does not replace the working cursor");
			var branch=blob.getRef(0);
			assertEquals(branch.getValue(),store.refForHash(branch.getHash()).getValue());
			assertEquals(retained,store.getRootData(),"Persist stores cells without selecting the root");
			assertEquals(updated,fresh.sync());
			assertEquals(updated,store.getRootData());
			fresh.flush();
			assertEquals(updated,LatticeHost.open(initial,store).cursor().get());

			fresh.cursor().set(null);
			assertNull(fresh.sync());
			assertNull(store.getRootData(),"An explicit null root clears the retained value");
		}
	}

	@Test
	void etchReopensThePublishedTree(@TempDir Path directory) throws Exception {
		var file=directory.resolve("reopen.etch").toFile();
		var value=Blobs.createRandom(new Random(7311),5000);
		try (var store=EtchStore.create(file)) {
			var host=LatticeHost.create(value,store);
			host.sync();
			host.flush();
		}
		try (var store=EtchStore.create(file)) {
			var restored=LatticeHost.open((ABlob)null,store);
			assertEquals(value,restored.cursor().get(),"All branches remain readable after reopening");
		}
	}

	@Test
	void explicitNullStoreDiscardsPublications() throws Exception {
		var store=NullStore.INSTANCE;
		var value=Blobs.createRandom(new Random(7312),5000);
		var host=LatticeHost.create(value,store);
		assertSame(store,host.store(),"An explicit NullStore is distinct from having no store");
		assertSame(value,host.persist());
		assertSame(value,host.sync());
		host.flush();
		assertSame(value,host.cursor().get());
		assertNull(store.getRootData());
		assertNull(store.refForHash(value.getHash()));
		assertNull(LatticeHost.open((ABlob)null,store).cursor().get());
	}

}
