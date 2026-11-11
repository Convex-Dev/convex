package convex.lattice.cursor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;

/** Claims stopping at a boundary, while writes go straight through it. */
class BoundaryCursorTest {

	private static final Keyword A=Keyword.intern("a");
	private static final Keyword B=Keyword.intern("b");
	private static final Keyword X=Keyword.intern("x");

	private static Root<AHashMap<Keyword,ACell>> tree() {
		return new Root<>(Maps.of(A,CVMLong.ZERO,B,CVMLong.ZERO));
	}

	private static BoundaryCursor<AInteger> boundary(Root<AHashMap<Keyword,ACell>> root,Keyword key) {
		return BoundaryCursor.over(root.<AInteger>path(key));
	}

	@Test void writesGoStraightThrough() {
		var root=tree();
		var a=boundary(root,A);
		a.set(CVMLong.ONE);
		assertEquals(CVMLong.ONE,root.get(A));
		assertEquals(CVMLong.TWO,a.updateAndGet(v -> CVMLong.TWO));
		assertTrue(a.compareAndSet(CVMLong.TWO,CVMLong.create(3)));
		assertEquals(CVMLong.create(3),root.get(A));
		root.set(root.get().assoc(A,CVMLong.create(4)));
		assertEquals(CVMLong.create(4),a.get(),"reads see the storage beneath");
	}

	@Test void aBoundaryOverABoundaryIsTheSameBoundary() {
		var a=boundary(tree(),A);
		assertSame(a,BoundaryCursor.over(a));
	}

	@Test void exclusiveTransactionsUnderSeparateBoundariesRunAtTheSameTime() {
		var root=tree();
		var a=boundary(root,A);
		var b=boundary(root,B);
		try (var other=Executors.newVirtualThreadPerTaskExecutor()) {
			Transactions.executeExclusively(a,draft -> {
				// Completes inside this callback, so it did not wait for this claim
				complete(other.submit(() -> Transactions.executeExclusively(b,d -> d.set(CVMLong.TWO))));
				assertEquals(CVMLong.TWO,root.get(B),"committed while this claim is held");
				draft.set(CVMLong.ONE);
			});
		}
		assertEquals(Maps.of(A,CVMLong.ONE,B,CVMLong.TWO),root.get());
	}

	@Test void withoutBoundariesTheRootsOneClaimMakesThemWait() throws InterruptedException {
		var root=tree();
		Thread[] other=new Thread[1];
		Transactions.executeExclusively(root.<AInteger>path(A),draft -> {
			other[0]=Thread.ofPlatform().start(() -> Transactions.executeExclusively(root.<AInteger>path(B),d -> d.set(CVMLong.TWO)));
			awaitParked(other[0]);
			assertTrue(other[0].isAlive(),"waits for the root's claim");
			draft.set(CVMLong.ONE);
		});
		other[0].join();
		assertEquals(Maps.of(A,CVMLong.ONE,B,CVMLong.TWO),root.get());
	}

	@Test void aWriteThroughAClaimedBoundaryWaitsAndLandsAfterTheCommit() throws InterruptedException {
		var root=tree();
		var a=boundary(root,A);
		Thread[] writer=new Thread[1];
		Transactions.executeExclusively(a,draft -> {
			writer[0]=Thread.ofPlatform().start(() -> a.updateAndGet(v -> CVMLong.create(v.longValue()+10)));
			awaitParked(writer[0]);
			assertTrue(writer[0].isAlive(),"waits for the claim");
			draft.set(CVMLong.ONE);
		});
		writer[0].join();
		assertEquals(CVMLong.create(11),root.get(A),"applied on top of the commit");
	}

	@Test void aPathOverABoundaryClaimsTheBoundary() throws InterruptedException {
		var root=new Root<AHashMap<Keyword,ACell>>(Maps.of(A,Maps.of(X,CVMLong.ZERO)));
		var a=BoundaryCursor.over(root.<AHashMap<Keyword,ACell>>path(A));
		Thread[] writer=new Thread[1];
		Transactions.executeExclusively(a.<AInteger>path(X),draft -> {
			writer[0]=Thread.ofPlatform().start(() -> a.set(Maps.of(X,CVMLong.TWO)));
			awaitParked(writer[0]);
			assertTrue(writer[0].isAlive());
			draft.set(CVMLong.ONE);
		});
		writer[0].join();
		assertEquals(CVMLong.TWO,root.get(A,X));
	}

	@Test void aClaimBeneathTheBoundaryStopsWritesThroughIt() throws InterruptedException {
		var root=tree();
		var a=boundary(root,A);
		Thread[] writer=new Thread[1];
		Transactions.executeExclusively(root,draft -> {
			writer[0]=Thread.ofPlatform().start(() -> a.set(CVMLong.TWO));
			awaitParked(writer[0]);
			assertTrue(writer[0].isAlive(),"a write through the boundary still passes the root's claim");
			draft.set(draft.get().assoc(B,CVMLong.ONE));
		});
		writer[0].join();
		assertEquals(Maps.of(A,CVMLong.TWO,B,CVMLong.ONE),root.get());
	}

	@Test void writesElsewhereCarryOnDuringABoundaryClaim() {
		var root=tree();
		var a=boundary(root,A);
		try (var other=Executors.newVirtualThreadPerTaskExecutor()) {
			Transactions.executeExclusively(a,draft -> {
				complete(other.submit(() -> root.<AInteger>path(B).set(CVMLong.TWO)));
				draft.set(CVMLong.ONE);
			});
		}
		assertEquals(Maps.of(A,CVMLong.ONE,B,CVMLong.TWO),root.get());
	}

	@Test void aWriteThatBypassesTheBoundaryMakesItsClaimedCommitFail() {
		var root=tree();
		var a=boundary(root,A);
		assertThrows(IllegalStateException.class,() -> Transactions.executeExclusively(a,draft -> {
			root.set(root.get().assoc(A,CVMLong.create(9)));
			draft.set(CVMLong.ONE);
		}));
		assertEquals(CVMLong.create(9),root.get(A),"never overwritten");
	}

	@Test void givenAMergeABypassingWriteIsMergedWithTheClaimedCommit() {
		var root=tree();
		var a=boundary(root,A);
		var merged=new ArrayList<ACell>();
		Transactions.executeExclusively(a,draft -> {
			root.set(root.get().assoc(A,CVMLong.create(9)));
			draft.set(CVMLong.ONE);
		},(base,own,other) -> {
			merged.addAll(List.of(base,own,other));
			return CVMLong.create(10);
		});
		assertEquals(List.of(CVMLong.ZERO,CVMLong.ONE,CVMLong.create(9)),merged,"the run's input, the run's value as its own, then the bypassing write");
		assertEquals(CVMLong.create(10),root.get(A));
	}

	@Test void aClaimedRunThatChangedNothingKeepsWhatABypassingWriteLeft() {
		var root=tree();
		var a=boundary(root,A);
		AInteger written=CVMLong.create(123456789);
		Transactions.executeExclusively(a,draft -> root.set(root.get().assoc(A,written)),(base,own,other) -> fail("nothing to merge"));
		assertSame(written,a.get(),"the destination's own cells, which may be persisted");
	}

	@Test void aBypassingWriteThatMatchesTheRunNeedsNoMerge() {
		var root=tree();
		var a=boundary(root,A);
		Transactions.executeExclusively(a,draft -> {
			root.set(root.get().assoc(A,CVMLong.ONE));
			draft.set(CVMLong.ONE);
		},(base,own,other) -> fail("both sides agree"));
		assertEquals(CVMLong.ONE,a.get());
	}

	@Test void aBoundaryCannotBeClaimedTwiceByOneThread() {
		var a=boundary(tree(),A);
		Transactions.executeExclusively(a,draft -> {
			assertThrows(IllegalStateException.class,() -> Transactions.executeExclusively(a,d -> d.set(CVMLong.TWO)));
			draft.set(CVMLong.ONE);
		});
		assertEquals(CVMLong.ONE,a.get());
	}

	@Test void persistingGoesThroughDuringAClaim() {
		var root=tree();
		var a=boundary(root,A);
		try (var other=Executors.newVirtualThreadPerTaskExecutor()) {
			Transactions.executeExclusively(a,draft -> {
				complete(other.submit(() -> { a.persist(value -> value); return null; }));
				draft.set(CVMLong.ONE);
			});
		}
		assertEquals(CVMLong.ONE,root.get(A));
	}

	/** Waits until a thread is parked, as one waiting for a claim is, or has finished. */
	private static void awaitParked(Thread thread) {
		while (thread.isAlive() && thread.getState()!=Thread.State.WAITING) Thread.onSpinWait();
	}

	private static void complete(Future<?> work) {
		try { work.get(10,TimeUnit.SECONDS); }
		catch (Exception e) { throw new AssertionError(e); }
	}
}
