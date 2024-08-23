package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import convex.core.Belief;
import convex.core.Block;
import convex.core.Constants;
import convex.core.Order;
import convex.core.Result;
import convex.core.State;
import convex.core.data.prim.CVMLong;
import convex.core.init.InitTest;
import convex.core.lang.RecordFormat;
import convex.core.lang.TestState;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Transfer;

public class RecordTest {
	
	// TODO: Check we have included all other record types
	// Transactions handled in TransactionTest which delegates to here
	// AccountStatusTest delegates to here
	// PeerStatusTest delegates to here
	// SignedDataTest delegates to here
	// BeliefMergeTest sends some instances here

	@Test
	public void testBelief() {
		Belief b=Belief.createSingleOrder(InitTest.FIRST_PEER_KEYPAIR);
		assertEquals(b.getRefCount(),b.getOrders().getRefCount());
		doRecordTests(b);
	}
	
	@Test
	public void testBlock() {
		Transfer tx1=Transfer.create(InitTest.HERO, 0, InitTest.VILLAIN, 1000);
		Transfer tx2=Transfer.create(InitTest.VILLAIN, 0, InitTest.HERO, 2000);
		SignedData<ATransaction> stx1=InitTest.HERO_KEYPAIR.signData(tx1);
		SignedData<ATransaction> stx2=InitTest.VILLAIN_KEYPAIR.signData(tx2);
		Block b=Block.create(Constants.INITIAL_TIMESTAMP+17, Vectors.of(stx1,stx2));
		doRecordTests(b);
	}

	@Test
	public void testOrder() {
		doRecordTests(Order.create());
	}

	@Test
	public void testState() {
		State s = InitTest.STATE;
		doRecordTests(s);
	}

	public static void doRecordTests(ARecord r) {
		RecordFormat format=r.getFormat();
		AVector<Keyword> keys=format.getKeys();
		int n=(int) keys.count();

		AVector<ACell> vals=r.values();
		assertEquals(n,vals.size());
		VectorsTest.doVectorTests(vals);

		ACell[] vs=new ACell[n]; // new array to extract values
		for (int i=0; i<n; i++) {
			// standard element access by key
			Keyword k=keys.get(i);
			ACell v=r.get(k);
			vs[i]=v;

			// entry based access by key
			MapEntry<Keyword,ACell> me0=r.getEntry(k);
			assertEquals(k,me0.getKey());
			assertEquals(v,me0.getValue());

			// TODO: consider this invariant?
			assertEquals(r.toHashMap(),r.assoc(k, v));

			// indexed access
			assertEquals(v,vals.get(i));

			// indexed entry-wise access
			MapEntry<Keyword,ACell> me=r.entryAt(i);
			assertEquals(k,me.getKey());
			assertEquals(v,me.getValue());
		}
		assertThrows(IndexOutOfBoundsException.class,()->r.entryAt(n));
		assertThrows(IndexOutOfBoundsException.class,()->r.entryAt(-1));

		int rc=r.getRefCount();
		for (int i=0; i<rc; i++) {
			r.getRef(i);
		}
		assertThrows(IndexOutOfBoundsException.class,()->r.getRef(rc));

		CollectionsTest.doDataStructureTests(r);
	}
	
	@Test
	public void testResult() {
		String s="{:id 4,:result #44,:error nil,:log nil,:info nil}";
		AHashMap<Keyword,ACell> m=TestState.eval(s);
		assertEquals(5,m.count);
	
		Result r=Result.create(CVMLong.create(4), Address.create(44), null, null);
		assertEquals(s,r.toString());
		
		assertEquals(m,r.toHashMap());
		
		doRecordTests(r);
	}
}
