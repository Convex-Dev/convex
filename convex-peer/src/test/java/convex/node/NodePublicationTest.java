package convex.node;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.ASet;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Ref;
import convex.core.data.RefSoft;
import convex.core.data.Sets;
import convex.core.data.prim.CVMLong;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.etch.Etch;
import convex.etch.EtchStore;
import convex.lattice.generic.LWWLattice;
import convex.lattice.generic.MapLattice;
import convex.lattice.generic.SetLattice;

/** Ordering and reference identity at the node/group/publication boundaries. */
public class NodePublicationTest {

	private static final Keyword LOCAL=Keyword.intern("local");
	private static final Keyword REMOTE=Keyword.intern("remote");

	/** Runs one deterministic intervening action after announce, before root publication. */
	private static class HookedEtchStore extends EtchStore {
		Runnable beforeRootWrite;

		HookedEtchStore() throws IOException {
			super(Etch.createTempEtch("node-publication"));
		}

		@Override
		public <T extends ACell> Ref<T> setRootData(T value) throws IOException {
			Runnable action=beforeRootWrite;
			beforeRootWrite=null;
			if (action!=null) action.run();
			return super.setRootData(value);
		}
	}

	@Test
	public void testLocalEditWinsReplicatedBackGroupTie() throws Exception {
		MapLattice<Keyword,CVMLong> lattice=MapLattice.create(LWWLattice.create(value -> 1L));
		try (MemoryStore nodeStore=new MemoryStore();
				MemoryStore viewStore=new MemoryStore();
				NodeServer<AHashMap<Keyword,CVMLong>> node=new NodeServer<>(lattice,nodeStore)) {
			CVMLong old=CVMLong.create(101);
			CVMLong edit=CVMLong.create(102);
			AHashMap<Keyword,CVMLong> premerged=Maps.of(LOCAL,old,REMOTE,CVMLong.create(103));
			LatticePropagator group=new LatticePropagator(viewStore,lattice,value -> value);
			// A group can accumulate values before it submits an aggregate to the node.
			group.processSnapshot(premerged);
			node.addPropagator(group);
			node.getCursor().set(Maps.of(LOCAL,old));
			node.launch();
			assertEquals(premerged,group.getLastAnnouncedValue());

			node.getCursor().assoc(LOCAL,edit);
			CompletableFuture<ACell> announced=group.nextAnnounce();
			NodeServer.MergeOutcome outcome=node.mergeInbound(new ACell[0],premerged);
			assertTrue(outcome.accepted());
			assertTrue(outcome.changed(),"the group's independent contribution must survive");
			AHashMap<Keyword,CVMLong> expected=premerged.assoc(LOCAL,edit);
			assertEquals(expected,node.getLocalValue(),"local edit wins against replicated-back input");
			assertEquals(expected,announced.get(5,TimeUnit.SECONDS),
				"the group's old value must not win again on the outbound path");

			assertFalse(node.mergeInbound(new ACell[0],premerged).changed());
			assertFalse(node.mergeInbound(new ACell[] {LOCAL},old).changed());
			assertEquals(expected,node.getLocalValue(),"root and path replays both retain the edit");
		}
	}

	@Test
	public void testAttachedGroupStillAcceptsStrictlyNewerValues() throws Exception {
		LWWLattice<CVMLong> lattice=LWWLattice.create(CVMLong::longValue);
		try (MemoryStore nodeStore=new MemoryStore();
				MemoryStore viewStore=new MemoryStore();
				NodeServer<CVMLong> node=new NodeServer<>(lattice,nodeStore)) {
			LatticePropagator group=new LatticePropagator(viewStore,lattice,value -> value);
			group.processSnapshot(CVMLong.create(20));
			node.addPropagator(group);
			node.getCursor().set(CVMLong.create(10));
			node.launch();
			assertEquals(CVMLong.create(20),group.getLastAnnouncedValue());
			node.mergeInbound(new ACell[0],group.getLastAnnouncedValue());
			assertEquals(CVMLong.create(20),node.getLocalValue());
		}
	}

	@Test
	public void testOldExplicitSnapshotCannotDemotePublishedRoot() throws Exception {
		try (MemoryStore store=new MemoryStore();
				NodeServer<ASet<CVMLong>> node=new NodeServer<>(SetLattice.create(),store)) {
			node.launch();
			ASet<CVMLong> old=Sets.of(CVMLong.ONE);
			node.getCursor().set(old.include(CVMLong.TWO));
			ACell published=node.getCursor().sync();
			node.persistSnapshot(old);
			assertSame(published,node.getLocalValue());
			assertSame(published,store.getRootData());
		}
	}

	@Test
	public void testExplicitSnapshotInstallsEtchRefsWithoutNotification() throws Exception {
		try (EtchStore store=EtchStore.createTemp("node-explicit-refs");
				MemoryStore viewStore=new MemoryStore();
				NodeServer<ASet<Blob>> node=new NodeServer<>(SetLattice.create(),store)) {
			AtomicInteger notifications=new AtomicInteger();
			LatticePropagator group=new LatticePropagator(viewStore,SetLattice.<Blob>create(),value -> value) {
				@Override public void triggerBroadcast(ACell value) {
					notifications.incrementAndGet();
				}
			};
			node.addPropagator(group);
			node.launch();
			node.getCursor().set(Sets.of(Blobs.createRandom(400),Blobs.createRandom(400)));
			node.persistSnapshot(node.getLocalValue());
			ACell published=store.getRootData();
			assertSame(published,node.getLocalValue());
			assertTrue(assertSoftBranches(published,store)>0);
			assertEquals(0,notifications.get());

			assertSame(published,node.getCursor().sync());
			assertEquals(1,notifications.get(),"explicit persistence must not replace the normal sync policy");
		}
	}

	@Test
	public void testAttachedPublicationUsesServingStoreRefs() throws Exception {
		try (EtchStore nodeStore=EtchStore.createTemp("node-root-refs");
				EtchStore viewStore=EtchStore.createTemp("node-view-refs");
				NodeServer<ASet<Blob>> node=new NodeServer<>(SetLattice.create(),nodeStore)) {
			LatticePropagator group=new LatticePropagator(viewStore,SetLattice.<Blob>create(),value -> value);
			node.addPropagator(group);
			node.getCursor().set(Sets.of(Blobs.createRandom(400),Blobs.createRandom(400)));
			node.launch();
			ACell root=node.getLocalValue();
			ACell view=group.getLastAnnouncedValue();
			assertEquals(root,view);
			assertTrue(assertSoftBranches(root,nodeStore)>0);
			assertTrue(assertSoftBranches(view,viewStore)>0);

			CompletableFuture<ACell> announced=group.nextAnnounce();
			node.getCursor().sync();
			ACell repeated=announced.get(5,TimeUnit.SECONDS);
			// Embedded roots may be rebuilt; the exact returned tree must be retained,
			// with actual branch refs rebound to the serving store on every publication.
			assertEquals(view,repeated);
			assertSame(repeated,group.getLastAnnouncedValue());
			assertTrue(assertSoftBranches(repeated,viewStore)>0);
			assertTrue(assertSoftBranches(node.getLocalValue(),nodeStore)>0);
		}
	}

	@Test
	public void testLocalAndRemoteWritesSurviveExplicitPersistence() throws Exception {
		MapLattice<Keyword,Blob> lattice=MapLattice.create(LWWLattice.create(value -> 1L));
		try (HookedEtchStore store=new HookedEtchStore();
				NodeServer<AHashMap<Keyword,Blob>> node=new NodeServer<>(lattice,store)) {
			node.launch();
			Blob old=Blobs.createRandom(400);
			Blob edit=Blobs.createRandom(400);
			Blob remote=Blobs.createRandom(400);
			AHashMap<Keyword,Blob> snapshot=Maps.of(LOCAL,old);
			node.getCursor().set(snapshot);
			store.beforeRootWrite=() -> {
				node.getCursor().assoc(LOCAL,edit);
				node.mergeValue(Maps.of(LOCAL,old,REMOTE,remote));
			};

			node.persistSnapshot(snapshot);
			assertEquals(snapshot,store.getRootData(),"only the completed snapshot is published");
			AHashMap<Keyword,Blob> expected=Maps.of(LOCAL,edit,REMOTE,remote);
			assertEquals(expected,node.getLocalValue(),"new local ties and independent remote writes survive");

			// Reusing a stale snapshot still publishes the current local-first merge.
			node.persistSnapshot(snapshot);
			assertEquals(expected,store.getRootData());
			assertSame(store.getRootData(),node.getLocalValue());
			assertTrue(assertSoftBranches(node.getLocalValue(),store)>0);
		}
	}

	@Test
	public void testRestoreKeepsPreLaunchLocalEditsAndPersistedContributions() throws Exception {
		MapLattice<Keyword,CVMLong> lattice=MapLattice.create(LWWLattice.create(value -> 1L));
		try (EtchStore store=EtchStore.createTemp("node-local-restore")) {
			store.setRootData(Maps.of(LOCAL,CVMLong.ONE,REMOTE,CVMLong.create(3)));
			try (NodeServer<AHashMap<Keyword,CVMLong>> node=new NodeServer<>(lattice,store)) {
				node.getCursor().set(Maps.of(LOCAL,CVMLong.TWO));
				node.launch();
				assertEquals(Maps.of(LOCAL,CVMLong.TWO,REMOTE,CVMLong.create(3)),node.getLocalValue());
				assertSame(store.getRootData(),node.getLocalValue());
			}
		}
	}

	@Test
	public void testWriteDuringLaunchPublicationRemainsPending() throws Exception {
		LWWLattice<CVMLong> lattice=LWWLattice.create(value -> 1L);
		try (HookedEtchStore store=new HookedEtchStore();
				NodeServer<CVMLong> node=new NodeServer<>(lattice,store)) {
			node.getCursor().set(CVMLong.ONE);
			store.beforeRootWrite=() -> node.getCursor().set(CVMLong.TWO);
			node.launch();
			assertEquals(CVMLong.ONE,store.getRootData());
			assertEquals(CVMLong.TWO,node.getLocalValue());
			node.getCursor().sync();
			assertEquals(CVMLong.TWO,store.getRootData());
		}
	}

	@Test
	public void testExplicitPersistencePreservesIOExceptionContract() throws Exception {
		try (HookedEtchStore store=new HookedEtchStore() {
			@Override public <T extends ACell> Ref<T> setRootData(T value) throws IOException {
				throw new IOException("publication failed");
			}
		}; NodeServer<ASet<CVMLong>> node=new NodeServer<>(SetLattice.create(),store)) {
			node.getCursor().set(Sets.of(CVMLong.ONE));
			IOException failure=assertThrows(IOException.class,() -> node.persistSnapshot(node.getLocalValue()));
			assertEquals("publication failed",failure.getMessage());
			assertNull(store.getRootData());
			assertEquals(Sets.of(CVMLong.ONE),node.getLocalValue());
		}
	}

	/** Inspect the references held by parents, not just each child's cached self-ref. */
	private static int assertSoftBranches(ACell value,AStore store) {
		if (value==null) return 0;
		int count=0;
		for (int i=0;i<value.getRefCount();i++) {
			Ref<ACell> ref=value.getRef(i);
			if (!ref.isEmbedded()) {
				RefSoft<?> soft=assertInstanceOf(RefSoft.class,ref);
				assertSame(store,soft.getStore());
				count++;
			}
			count+=assertSoftBranches(ref.getValue(),store);
		}
		return count;
	}
}
