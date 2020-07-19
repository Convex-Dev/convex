package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import convex.core.Block;
import convex.core.Init;
import convex.core.crypto.AKeyPair;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.BadSignatureException;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Transfer;
import convex.test.Samples;

public class BlocksTest {
	@Test
	public void testEquality() throws BadFormatException {
		long ts = System.currentTimeMillis();
		Block b1 = Block.create(ts, Vectors.empty(),Init.FIRST_PEER);
		Block b2 = Block.create(ts, Vectors.empty(),Init.FIRST_PEER);

		assertEquals(b1, b2);
		assertEquals(b1.hashCode(), b2.hashCode());
		assertEquals(b1.getHash(), b2.getHash());
		assertEquals(b1.getEncoding(), b2.getEncoding());
		assertEquals(b1, Format.read(b2.getEncoding()));
	}

	@Test
	public void testTransactions() throws BadSignatureException {
		AKeyPair kp = Samples.KEY_PAIR;
		Address addr = kp.getAddress();

		ATransaction t = Transfer.create(0, addr, 1000);
		SignedData<ATransaction> st = kp.signData(t);

		long ts = System.currentTimeMillis();
		Block b = Block.create(ts, Vectors.of(st),Init.FIRST_PEER);
		assertEquals(1, b.length());
		assertEquals(t, b.getTransactions().get(0).getValue());
		
		RecordTest.doRecordTests(b);

	}
}
