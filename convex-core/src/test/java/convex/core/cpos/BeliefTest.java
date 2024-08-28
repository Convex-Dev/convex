package convex.core.cpos;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import convex.core.Belief;
import convex.core.Block;
import convex.core.Order;
import convex.core.crypto.AKeyPair;
import convex.core.data.AccountKey;
import convex.core.data.Address;
import convex.core.data.Cells;
import convex.core.data.EncodingTest;
import convex.core.data.RecordTest;
import convex.core.data.SignedData;
import convex.core.exceptions.BadFormatException;
import convex.core.transactions.Invoke;
import convex.test.Samples;

public class BeliefTest {
	static final int PEERS=4;
	static final AKeyPair[] kps=new AKeyPair[PEERS];
	static final AccountKey[] keys=new AccountKey[PEERS];
	
	static {
		for (int i=0; i<PEERS; i++) {
			AKeyPair kp=AKeyPair.createSeeded(i+789798);
			kps[i]=kp;
			keys[i]=kp.getAccountKey();
		}
	}
	
	@SuppressWarnings("unchecked")
	@Test public void testBasicBelief() throws BadFormatException, IOException {
		Order o=Order.create();
		for (int i=0; i<PEERS; i++) {
			AKeyPair kp=kps[i];
			Block bi=Block.of(i, kp.signData(Invoke.create(Address.create(i), 1L, Samples.NON_EMBEDDED_STRING)));
			o=o.append(kp.signData(bi));
		}
		SignedData<Order>[] orders=new SignedData[PEERS];
		for (int i=0; i<PEERS; i++) {
			AKeyPair kp=kps[i];
			orders[i]=kp.signData(o);
		}		
		Belief b=Belief.create(orders);
		
		RecordTest.doRecordTests(b);
		
		b=Cells.persist(b);
		
		RecordTest.doRecordTests(b);
		
		EncodingTest.testFullencoding(b);
	}
}
