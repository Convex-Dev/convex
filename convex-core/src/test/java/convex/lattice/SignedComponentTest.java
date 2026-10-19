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
	public void testSignedLocalEditWinsAnEqualTimestampConflict() {
		var lattice=OwnerKeyLattice.create(LWWLattice.<CVMLong>create(v -> 100)).signingFor(KEY.getAccountKey());
		var destination=new Root<>(KEY.signData(CVMLong.ZERO));
		var component=new SignedComponent<>(lattice,destination,KEY::signData);
		component.cursor().set(CVMLong.ONE);
		destination.set(KEY.signData(CVMLong.TWO));
		component.sync();
		assertEquals(CVMLong.ONE,destination.get().getValue());
		assertTrue(destination.get().checkSignature());
		assertEquals(CVMLong.ONE,component.merge(KEY.signData(CVMLong.TWO)),
			"Incoming ties also preserve the existing own value");
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
		new ALatticeComponent<ASet<CVMLong>>(component.cursor()) {}.sync();
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
	public void testUnrelatedAncestorCASFailureReusesSignature() {
		Keyword slot=Keyword.intern("owner");
		Keyword sibling=Keyword.intern("sibling");
		AtomicInteger attempts=new AtomicInteger();
		var root=new Root<>(Index.<Keyword,ACell>none()) {
			@Override public boolean compareAndSet(Index<Keyword,ACell> expected,Index<Keyword,ACell> value) {
				if (attempts.incrementAndGet()==1) set(get().assoc(sibling,CVMLong.ONE));
				return super.compareAndSet(expected,value);
			}
		};
		var destination=root.<SignedData<ASet<CVMLong>>>path(slot);
		AtomicInteger signatures=new AtomicInteger();
		var component=new SignedComponent<>(SIGNED,destination,signer(signatures::incrementAndGet));
		component.cursor().set(Sets.of(1));
		component.sync();
		assertEquals(1,signatures.get());
		assertEquals(2,attempts.get());
		assertEquals(CVMLong.ONE,root.get().get(sibling));
		assertEquals(Sets.of(1),destination.get().getValue());
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
		var component=new SignedComponent<>(SIGNED,destination,signer(() -> {
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
	public void testEditsDuringSigningRemainPending() throws Exception {
		var destination=new Root<SignedData<ASet<CVMLong>>>();
		CountDownLatch signing=new CountDownLatch(1);
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
		try (var executor=Executors.newSingleThreadExecutor()) {
			var publishing=executor.submit(component::sync);
			try {
				assertTrue(signing.await(10,TimeUnit.SECONDS));
				component.cursor().updateAndGet(v -> v.include(CVMLong.TWO));
				assertNull(destination.get());
			} finally { resume.countDown(); }
			publishing.get(10,TimeUnit.SECONDS);
		}
		assertEquals(Sets.of(1),destination.get().getValue());
		assertEquals(Sets.of(1,2),component.cursor().get());
		component.sync();
		assertEquals(Sets.of(1,2),destination.get().getValue());
		assertEquals(2,signatures.get());
	}
}
