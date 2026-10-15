package convex.lattice.cursor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.Utils;
import convex.lattice.ALattice;
import convex.lattice.LatticeContext;
import convex.lattice.generic.JSONLattice;
import convex.lattice.generic.LWWLattice;

/**
 * End-to-end test of the Covia {@code :state} composition: a whole-value-LWW leaf
 * with a component-supplied stamping function that
 *
 * <ul>
 *   <li>merges whole-value by timestamp so deletions survive merge-back;</li>
 *   <li>uses component-supplied construction below the leaf;</li>
 *   <li>re-stamps the whole leaf on every deep write via an inserted
 *       {@link StampedCursor}.</li>
 * </ul>
 */
public class StampedCursorTest {

	@Test public void testRawForkAdoptsPreparedDestinationValue() {
		Root<ACell> root=Root.create(Maps.of(A,CVMLong.ZERO));
		var stamped=StampedCursor.create(root,() -> CVMLong.create(100),(value,time) -> stampWith(value,time.longValue()));
		var fork=stamped.fork();
		fork.set(Maps.of(A,CVMLong.ONE));
		ACell published=fork.sync();
		assertSame(root.get(),published);
		assertSame(published,fork.get());
		assertEquals(100,tsOf(published));
	}

	@Test public void testChangedRetryKeepsOneOperationTimestamp() {
		Root<ACell> root=Root.create(Maps.of(A,CVMLong.ZERO,B,CVMLong.ZERO));
		AtomicInteger clocks=new AtomicInteger();
		AtomicInteger preparations=new AtomicInteger();
		LatticeContext context=new LatticeContext() {
			@Override public CVMLong currentTimestamp() {
				return CVMLong.create(clocks.incrementAndGet());
			}
		};
		StampedCursor<ACell> cursor=StampedCursor.create(root,context::currentTimestamp,(value,time) -> {
			// Force the first candidate to lose its CAS to a different field update.
			if (preparations.incrementAndGet()==1) root.set(Maps.of(A,CVMLong.ZERO,B,CVMLong.ONE));
			return stampWith(value,time.longValue());
		});
		cursor.updateAndGet(value -> RT.assoc(RT.ensureMap(value),A,CVMLong.ONE));
		assertEquals(2,preparations.get());
		assertEquals(1,clocks.get());
		assertEquals(CVMLong.ONE,RT.getIn(cursor.get(),A));
		assertEquals(CVMLong.ONE,RT.getIn(cursor.get(),B));
		assertEquals(1,tsOf(cursor.get()));

		cursor.set(cursor.get());
		cursor.set(null);
		assertEquals(1,clocks.get(),"No-op writes and removal do not resolve a timestamp");
		cursor.set(Maps.of(A,CVMLong.ZERO));
		assertEquals(2,clocks.get(),"The next operation resolves its own timestamp");
		assertEquals(2,tsOf(cursor.get()));
	}

	static final AString USERS = Strings.create("users");
	static final AString ALICE = Strings.create("alice");
	static final AString A = Strings.create("a");
	static final AString B = Strings.create("b");

	@SuppressWarnings("unchecked")
	static long tsOf(ACell v) {
		if (v instanceof AHashMap<?, ?> m) {
			ACell t = ((AHashMap<Keyword, ACell>) m).get(LWWLattice.KEY_TIMESTAMP);
			if (t instanceof CVMLong l) return l.longValue();
		}
		return 0;
	}

	@SuppressWarnings("unchecked")
	static ACell stampWith(ACell v, long ts) {
		if (v instanceof AHashMap<?, ?> m) {
			return ((AHashMap<Keyword, ACell>) m).assoc(LWWLattice.KEY_TIMESTAMP, CVMLong.create(ts));
		}
		return v;
	}

	static ALattice<ACell> stateLattice() {
		return LWWLattice.create(JSONLattice.INSTANCE, StampedCursorTest::tsOf);
	}

	/** The component explicitly chooses maps at both levels. */
	static AForkableCursor<ACell> userView(AForkableCursor<ACell> source) {
		return ProjectionCursor.create(source,
			value -> RT.getIn(value, USERS, ALICE),
			(value, child) -> {
				if (Utils.equals(RT.getIn(value, USERS, ALICE), child)) return value;
				AHashMap<ACell, ACell> parent = value == null ? Maps.empty() : RT.ensureMap(value);
				ACell currentUsers = parent.get(USERS);
				AHashMap<ACell, ACell> users = currentUsers == null ? Maps.empty() : RT.ensureMap(currentUsers);
				return parent.assoc(USERS, users.assoc(ALICE, child));
			});
	}

	// ===== Deep write below the leaf: structural + re-stamped =====

	@Test public void testDeepWriteStampsWholeLeaf() {
		ACell initial = Maps.of(LWWLattice.KEY_TIMESTAMP, CVMLong.create(5));
		// the write clock lives in the context (ts = 100)
		LatticeContext ctx = LatticeContext.create(CVMLong.create(100), null);
		Root<ACell> root = Cursors.root(initial);

		// the component owning the stamped leaf wraps it, then navigates below
		AForkableCursor<ACell> c = userView(StampedCursor.create(root,ctx::currentTimestamp,(v, ts) -> stampWith(v, ts.longValue())));
		c.set(Strings.create("bob"));

		// The component write function constructs the missing users map atomically.
		assertEquals(Strings.create("bob"), RT.getIn(root.get(), USERS, ALICE));
		// the whole :state leaf was re-stamped from the context timestamp
		assertEquals(100L, tsOf(root.get()), "deep write must re-stamp the whole :state leaf from context");
		// the timestamp keyword still coexists with the data at the top level
		assertNull(RT.getIn(root.get(), Strings.create("nope")));
	}

	@Test public void testLongLivedDerivedPathUsesAdvancedContextTimestamp() {
		ACell initial = Maps.of(LWWLattice.KEY_TIMESTAMP, CVMLong.create(5));
		Root<ACell> root = Cursors.root(initial);

		// Keep this derived cursor across a harness clock advance (#640).
		var clock=new java.util.concurrent.atomic.AtomicReference<>(CVMLong.create(100));
		AForkableCursor<ACell> child = userView(StampedCursor.create(root,clock::get,(v, ts) -> stampWith(v, ts.longValue())));
		clock.set(CVMLong.create(200));
		child.set(Strings.create("bob"));

		assertEquals(Strings.create("bob"), RT.getIn(root.get(), USERS, ALICE));
		assertEquals(200L, tsOf(root.get()),
			"a long-lived derived cursor must stamp with the parent’s current context time");
	}

	// ===== Whole-value LWW merge: deletions are durable =====

	@Test public void testWholeValueMergeDeletionDurable() {
		ALattice<ACell> state = stateLattice();
		ACell older = Maps.of(LWWLattice.KEY_TIMESTAMP, CVMLong.create(10), A, CVMLong.ONE, B, CVMLong.create(2));
		ACell newer = Maps.of(LWWLattice.KEY_TIMESTAMP, CVMLong.create(20), A, CVMLong.ONE); // "b" deleted

		// newer wins whole-value regardless of argument order — no per-key union
		assertSame(newer, state.merge(older, newer));
		assertSame(newer, state.merge(newer, older));
		// so the deleted key does NOT resurrect
		assertNull(RT.getIn(state.merge(older, newer), B));
	}

	// ===== Merge must NOT re-stamp the LWW winner =====

	@Test public void testCursorMergeDoesNotRestampWinner() {
		ACell older = Maps.of(A, CVMLong.ONE, LWWLattice.KEY_TIMESTAMP, CVMLong.create(10));
		ACell newer = Maps.of(B, CVMLong.create(2), LWWLattice.KEY_TIMESTAMP, CVMLong.create(20));
		Root<ACell> base = Root.create(older);
		ALattice<ACell> lww = LWWLattice.create(JSONLattice.INSTANCE, StampedCursorTest::tsOf);
		// stampFn would bump the timestamp to 999 if it were (wrongly) applied to a merge result
		StampedCursor<ACell> sc = StampedCursor.create(base,LatticeContext.EMPTY::currentTimestamp,(v, ts) -> stampWith(v, 999));

		base.updateAndGet(own -> lww.merge(own,newer)); // newer (ts 20) is the LWW winner

		assertEquals(20L, tsOf(sc.get()), "merge must not re-stamp the LWW winner");
		assertSame(newer, base.get());   // the exact winner value, stored unstamped
	}

	// ===== Navigation delegates to the inner JSON lattice =====

	@Test public void testNavigationDelegatesToInner() {
		ALattice<ACell> state = stateLattice();
		assertSame(JSONLattice.INSTANCE, state.path(USERS));
		assertSame(JSONLattice.INSTANCE.zero(), state.zero()); // both null (structural)
	}
}
