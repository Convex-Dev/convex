package convex.p2p;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.SignedData;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.ALatticeComponent;
import convex.lattice.LatticeApplication;
import convex.lattice.cursor.Root;

/** Domain storage properties need neither a node nor a disk store. */
class P2PComponentStorageTest {
	private static final AKeyPair ALICE=AKeyPair.createSeeded(7500);
	private static final AKeyPair BOB=AKeyPair.createSeeded(7501);
	private static final AccountKey KEY=ALICE.getAccountKey();
	private static final AHashMap<Keyword,ACell> FIRST=
		P2PLattice.createIdentity(Strings.intern("alice"),null,null,1);
	private static final AHashMap<Keyword,ACell> SECOND=
		P2PLattice.createIdentity(Strings.intern("updated"),null,null,2);

	private static P2PUser user(Root<ACell> root,BiFunction<ACell,ACell,SignedData<ACell>> signer) {
		var application=new LatticeApplication<Index<Keyword,ACell>>(P2PLattice.ROOT,root.path()) {};
		return P2PUser.create(application,KEY,signer);
	}

	private static ALatticeComponent<ACell> component(P2PUser user,boolean node) {
		return node?user.node():user.identity();
	}

	@Test void missingOrWrongSignerLeavesAllAncestorsAbsent() {
		for (boolean node:new boolean[] {false,true}) assertFailedSigning(node);
	}

	private void assertFailedSigning(boolean node) {
		for (int mode=0;mode<3;mode++) {
			var root=new Root<ACell>();
			BiFunction<ACell,ACell,SignedData<ACell>> signer=switch (mode) {
				case 0 -> null;
				case 1 -> (owner,value) -> null;
				default -> (owner,value) -> BOB.signData(value);
			};
			var view=component(user(root,signer),node);
			assertNull(view.cursor().get());
			view.cursor().set(null);
			view.sync();
			assertNull(root.get());
			assertThrows(IllegalStateException.class,() -> view.cursor().set(FIRST));
			assertNull(root.get(),"Failed signing must not create even an enclosing region");
		}
	}

	@Test void incompatibleAncestorsThrowWithoutReplacement() {
		for (boolean node:new boolean[] {false,true}) assertIncompatibleAncestors(node);
	}

	private void assertIncompatibleAncestors(boolean node) {
		var root=new Root<ACell>();
		var view=component(user(root,(owner,value) -> ALICE.signData(value)),node);
		List<ACell> invalid=node
			?List.of(CVMLong.ONE,Maps.empty(),Index.of(P2PLattice.KEY_P2P,Maps.empty()),
				Index.of(P2PLattice.KEY_P2P,Index.of(P2PLattice.KEY_NODES,Index.none())))
			:List.of(CVMLong.ONE,Maps.empty(),Index.of(P2PLattice.KEY_ID,CVMLong.ONE),
				Index.of(P2PLattice.KEY_ID,Index.none()));
		for (ACell value:invalid) {
			root.set(value);
			assertThrows(IllegalStateException.class,() -> view.cursor().get());
			assertThrows(IllegalStateException.class,() -> view.cursor().set(FIRST));
			assertSame(value,root.get());
		}
	}

	@Test void transactionsSignOnceAndAbortWithoutPublishing() {
		for (boolean node:new boolean[] {false,true}) assertTransactionBoundary(node);
	}

	private void assertTransactionBoundary(boolean node) {
		var root=new Root<ACell>();
		var signatures=new AtomicInteger();
		var user=user(root,(owner,value) -> {
			assertEquals(KEY,owner);
			signatures.incrementAndGet();
			return ALICE.signData(value);
		});
		Consumer<ALatticeComponent<ACell>> edits=draft -> {
			draft.cursor().set(FIRST);
			draft.cursor().set(SECOND);
			assertNull(root.get());
			assertEquals(0,signatures.get());
		};
		if (node) user.node().transact(edits::accept);
		else user.identity().transact(edits::accept);
		assertEquals(SECOND,component(user,node).cursor().get());
		assertEquals(1,signatures.get());
		ACell before=root.get();
		Consumer<ALatticeComponent<ACell>> abort=draft -> {
			draft.cursor().set(FIRST);
			throw new IllegalArgumentException("Aborted edit");
		};
		assertThrows(IllegalArgumentException.class,() -> {
			if (node) user.node().transact(abort::accept);
			else user.identity().transact(abort::accept);
		});
		assertSame(before,root.get());
		assertEquals(1,signatures.get());
	}

	/** A root conflict during signing must preserve both unrelated regions and owners. */
	@Test void forkPublicationRetainsConcurrentOpaqueSiblings() {
		for (boolean node:new boolean[] {false,true}) assertForkPublication(node);
	}

	private void assertForkPublication(boolean node) {
		var attempts=new AtomicInteger();
		var root=new Root<ACell>() {
			@Override public ACell updateAndGet(UnaryOperator<ACell> update) {
				return super.updateAndGet(current -> {
					attempts.incrementAndGet();
					return update.apply(current);
				});
			}
		};
		var signatures=new AtomicInteger();
		var opaque=Strings.intern("future data");
		var owners=Maps.of(BOB.getAccountKey(),opaque);
		Index<Keyword,ACell> concurrent=Index.of(P2PLattice.KEY_KAD,opaque);
		concurrent=node?concurrent.assoc(P2PLattice.KEY_P2P,Index.of(P2PLattice.KEY_NODES,owners))
			:concurrent.assoc(P2PLattice.KEY_ID,owners);
		var winner=concurrent;
		var user=user(root,(owner,value) -> {
			if (signatures.incrementAndGet()==1) root.set(winner);
			return ALICE.signData(value);
		});
		var draft=node?user.node().fork():user.identity().fork();
		draft.cursor().set(FIRST);
		draft.cursor().set(SECOND);
		assertNull(root.get());
		assertEquals(0,signatures.get());
		draft.sync();
		assertEquals(2,attempts.get(),"A conflicting root write must force a retry");
		assertEquals(1,signatures.get(),"An unchanged candidate reuses its prepared signature");
		assertEquals(SECOND,component(user,node).cursor().get());
		var actual=(Index<?,?>)root.get();
		assertSame(opaque,actual.get(P2PLattice.KEY_KAD));
		ACell region=node?((Index<?,?>)actual.get(P2PLattice.KEY_P2P)).get(P2PLattice.KEY_NODES)
			:actual.get(P2PLattice.KEY_ID);
		var stored=(AHashMap<?,?>)region;
		assertSame(opaque,stored.get(BOB.getAccountKey()));
		var signed=(SignedData<?>)stored.get(KEY);
		assertEquals(KEY,signed.getAccountKey());
		assertTrue(signed.checkSignature());
	}
}
