package convex.lattice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.ASet;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Sets;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.Root;
import convex.lattice.generic.OwnerKeyLattice;
import convex.lattice.generic.LWWLattice;
import convex.lattice.generic.SetLattice;
import convex.lattice.generic.SignedLattice;

public class SignedComponentTest {

	private static final AKeyPair KEY=AKeyPair.createSeeded(7310);
	private static final SignedLattice<ASet<CVMLong>> SIGNED=
		OwnerKeyLattice.create(SetLattice.<CVMLong>create()).signingFor(KEY.getAccountKey());

	private static <V extends ACell> Function<V,SignedData<V>> signer(Runnable signing) {
		return value -> {
			signing.run();
			return KEY.signData(value);
		};
	}

	@Test
	public void testNestedDraftsReconcileUnsignedAndSignOnlyAtTheEnvelopeBoundary() {
		var destination=new Root<SignedData<ASet<CVMLong>>>();
		var signatures=new AtomicInteger();
		var notebook=new SignedSetComponent<>(SIGNED,destination,signer(signatures::incrementAndGet));
		var first=notebook.fork();
		var second=notebook.fork();
		new SetComponent<>(first.cursor()).add(CVMLong.ONE);
		new SetComponent<>(second.cursor()).add(CVMLong.TWO);
		assertNull(notebook.cursor().get());
		first.sync();
		second.sync();
		assertEquals(Sets.of(1,2),notebook.cursor().get());
		assertNull(destination.get());
		assertEquals(0,signatures.get());
		notebook.sync();
		assertEquals(Sets.of(1,2),destination.get().getValue());
		assertTrue(destination.get().checkSignature());
		assertEquals(1,signatures.get());
		first.sync(); // Adopt the sibling's contribution without publishing the notebook.
		notebook.sync();
		assertEquals(Sets.of(1,2),first.cursor().get());
		assertEquals(1,signatures.get(),"Unchanged publication must not sign again");
	}

	/** A whole-record payload keeps one side, so local edits on both sides conflict; incoming ties keep the own value. */
	@Test
	public void testLocalEditsToAWholeRecordOnBothSidesConflict() {
		var lattice=OwnerKeyLattice.create(LWWLattice.<CVMLong>create(v -> 100)).signingFor(KEY.getAccountKey());
		var destination=new Root<>(KEY.signData(CVMLong.ZERO));
		var component=new SignedComponent<>(lattice,destination,KEY::signData);
		component.cursor().set(CVMLong.ONE);
		var theirs=KEY.signData(CVMLong.TWO);
		destination.set(theirs);
		assertThrows(MergeConflictException.class,component::sync);
		assertSame(theirs,destination.get());
		assertEquals(CVMLong.ONE,component.cursor().get());
		assertEquals(CVMLong.ONE,component.merge(KEY.signData(CVMLong.TWO)),
			"Incoming ties preserve the existing own value");
	}

	@Test
	public void testIncomingMergeVerifiesOwnerAndDefersSigning() {
		var destination=new Root<SignedData<ASet<CVMLong>>>();
		AtomicInteger signatures=new AtomicInteger();
		var component=new SignedComponent<>(SIGNED,destination,signer(signatures::incrementAndGet));
		component.cursor().set(Sets.of(1));
		component.merge(AKeyPair.createSeeded(7311).signData(Sets.of(3)));
		assertEquals(Sets.of(1),component.cursor().get());
		component.merge(KEY.signData(Sets.of(2)));
		component.merge(KEY.signData(Sets.of(2)));
		assertEquals(Sets.of(1,2),component.cursor().get());
		assertEquals(0,signatures.get());
		assertNull(destination.get());
		component.sync();
		assertEquals(Sets.of(1,2),destination.get().getValue());
		assertEquals(1,signatures.get());
	}

	@Test
	public void testBatchSigningAndUnchangedPublication() {
		var destination=new Root<SignedData<ASet<CVMLong>>>();
		AtomicInteger signatures=new AtomicInteger();
		var component=new SignedComponent<>(SIGNED,destination,signer(signatures::incrementAndGet));
		component.cursor().set(Sets.of(1));
		component.cursor().updateAndGet(v -> v.include(CVMLong.TWO));
		assertNull(destination.get());
		assertEquals(0,signatures.get());
		component.sync();
		assertEquals(Sets.of(1,2),destination.get().getValue());
		assertTrue(SIGNED.checkForeign(destination.get()));
		assertEquals(1,signatures.get());
		component.sync();
		assertEquals(1,signatures.get());
		assertTrue(destination.get().checkSignature());
	}

	@Test
	public void testBoundVerifierRejectsPublication() {
		var destination=new Root<SignedData<ASet<CVMLong>>>();
		var rejecting=SIGNED.withOwner(KEY.getAccountKey(),(owner,signer) -> false);
		var component=new SignedComponent<>(rejecting,destination,KEY::signData);
		component.cursor().set(Sets.of(1));
		assertThrows(IllegalStateException.class,component::sync);
		assertNull(destination.get());
		assertEquals(Sets.of(1),component.cursor().get());
	}

	@Test
	public void testUnrelatedAncestorWriteDuringSigningKeepsBothWrites() {
		Keyword slot=Keyword.intern("owner");
		Keyword sibling=Keyword.intern("sibling");
		var root=new Root<>(Index.<Keyword,ACell>none());
		var destination=new IndexComponent<>(root).<SignedData<ASet<CVMLong>>>path(slot);
		AtomicInteger signatures=new AtomicInteger();
		var component=new SignedComponent<>(SIGNED,destination,signer(() -> {
			// Change the ancestor after the update reads it, forcing a CAS retry.
			if (signatures.incrementAndGet()==1) root.updateAndGet(value -> value.assoc(sibling,CVMLong.ONE));
		}));
		component.cursor().set(Sets.of(1));
		component.sync();
		assertEquals(CVMLong.ONE,root.get().get(sibling));
		assertEquals(Sets.of(1),destination.get().getValue());
		assertTrue(destination.get().checkSignature());
	}

	@Test
	public void testMissingSignerCanBeSuppliedAfterFailedCheckpoint() {
		var destination=new Root<SignedData<ASet<CVMLong>>>();
		var key=new AtomicReference<AKeyPair>();
		var component=new SignedComponent<>(SIGNED,destination,value -> key.get()==null?null:key.get().signData(value));
		component.cursor().set(Sets.of(1));
		assertThrows(IllegalStateException.class,component::sync);
		assertNull(destination.get());
		key.set(KEY);
		component.sync();
		assertEquals(Sets.of(1),destination.get().getValue());
	}

	@Test
	public void testChangedDestinationPayloadIsMergedAndResigned() {
		var destination=new Root<SignedData<ASet<CVMLong>>>();
		AtomicInteger signatures=new AtomicInteger();
		var component=new SignedSetComponent<>(SIGNED,destination,signer(() -> {
			if (signatures.incrementAndGet()==1) destination.set(KEY.signData(Sets.of(2)));
		}));
		component.cursor().set(Sets.of(1));
		component.sync();
		assertEquals(2,signatures.get());
		assertEquals(Sets.of(1,2),destination.get().getValue());
		assertTrue(destination.get().checkSignature());
		assertEquals(Sets.of(1,2),component.cursor().get());
	}

	@Test
	public void testEditsDuringSigningCommitAfterSync() throws Exception {
		var destination=new Root<SignedData<ASet<CVMLong>>>();
		CountDownLatch signing=new CountDownLatch(1);
		CountDownLatch editing=new CountDownLatch(1);
		CountDownLatch resume=new CountDownLatch(1);
		AtomicInteger signatures=new AtomicInteger();
		var component=new SignedComponent<>(SIGNED,destination,signer(() -> {
			if (signatures.incrementAndGet()==1) {
				signing.countDown();
				try { assertTrue(resume.await(10,TimeUnit.SECONDS)); }
				catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
			}
		}));
		component.cursor().set(Sets.of(1));
		try (var executor=Executors.newVirtualThreadPerTaskExecutor()) {
			var publishing=executor.submit(component::sync);
			try {
				assertTrue(signing.await(10,TimeUnit.SECONDS));
				var edit=executor.submit(() -> component.cursor().updateAndGet(value -> {
					editing.countDown();
					return value.include(CVMLong.TWO);
				}));
				assertTrue(editing.await(10,TimeUnit.SECONDS));
				assertNull(destination.get());
				resume.countDown();
				publishing.get(10,TimeUnit.SECONDS);
				edit.get(10,TimeUnit.SECONDS);
			} finally { resume.countDown(); }
		}
		assertEquals(Sets.of(1),destination.get().getValue());
		assertEquals(Sets.of(1,2),component.cursor().get());
		component.sync();
		assertEquals(Sets.of(1,2),destination.get().getValue());
		assertEquals(2,signatures.get());
	}

	@Test
	public void testStoredEnvelopesBothChangedReconcileByThePayloadMergeAndSignTheResult() {
		var signatures=new AtomicInteger();
		var component=new SignedSetComponent<>(SIGNED,new Root<SignedData<ASet<CVMLong>>>(),signer(signatures::incrementAndGet));
		ACell result=component.reconcileStored(KEY.signData(Sets.of(1)),KEY.signData(Sets.of(1,2)),KEY.signData(Sets.of(1,3)));
		SignedData<?> envelope=(SignedData<?>)result;
		assertEquals(Sets.of(1,2,3),envelope.getValue());
		assertTrue(envelope.checkSignature());
		assertEquals(KEY.getAccountKey(),envelope.getAccountKey());
		assertEquals(1,signatures.get());
	}

	@Test
	public void testAReconciledPayloadEqualToOneSideKeepsThatSidesEnvelope() {
		var signatures=new AtomicInteger();
		var component=new SignedSetComponent<>(SIGNED,new Root<SignedData<ASet<CVMLong>>>(),signer(signatures::incrementAndGet));
		var own=KEY.signData(Sets.of(1,2));
		assertSame(own,component.reconcileStored(KEY.signData(Sets.of(1)),own,KEY.signData(Sets.of(2))));
		assertEquals(0,signatures.get(),"an existing envelope is never signed again");
	}

	@Test
	public void testANewReconciledPayloadWithoutASignerIsAConflict() {
		var component=new SignedSetComponent<>(SIGNED,new Root<ASet<CVMLong>>());
		assertThrows(MergeConflictException.class,() ->
			component.reconcileStored(KEY.signData(Sets.of(1)),KEY.signData(Sets.of(1,2)),KEY.signData(Sets.of(1,3))));
	}

	@Test
	public void testANewReconciledPayloadWithAnUnauthorisedSignerIsAConflict() {
		var stranger=AKeyPair.createSeeded(7311);
		Function<ASet<CVMLong>,SignedData<ASet<CVMLong>>> signer=stranger::signData;
		var component=new SignedSetComponent<>(SIGNED,new Root<SignedData<ASet<CVMLong>>>(),signer);
		assertThrows(MergeConflictException.class,() ->
			component.reconcileStored(KEY.signData(Sets.of(1)),KEY.signData(Sets.of(1,2)),KEY.signData(Sets.of(1,3))));
	}

	@Test
	public void testStoredEnvelopesBothChangedConflictWhereThePayloadCannotMerge() {
		var component=new SignedComponent<>(SIGNED,new Root<SignedData<ASet<CVMLong>>>(),KEY::signData);
		assertThrows(MergeConflictException.class,() ->
			component.reconcileStored(KEY.signData(Sets.of(1)),KEY.signData(Sets.of(1,2)),KEY.signData(Sets.of(1,3))));
	}
}
