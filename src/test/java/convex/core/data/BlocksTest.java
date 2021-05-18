package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import convex.core.Block;
import convex.core.Init;
import convex.core.crypto.AKeyPair;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.BadSignatureException;
import convex.core.lang.TestState;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Transfer;

public class BlocksTest {
	@Test
	public void testEquality() throws BadFormatException {
		long ts = System.currentTimeMillis();
		Block b1 = Block.create(ts, TestState.FIRST_PEER_KEY,Vectors.empty());
		Block b2 = Block.create(ts, TestState.FIRST_PEER_KEY,Vectors.empty());

		assertEquals(b1, b2);
		assertEquals(b1.hashCode(), b2.hashCode());
		assertEquals(b1.getHash(), b2.getHash());
		assertEquals(b1.getEncoding(), b2.getEncoding());
		assertEquals(b1, Format.read(b2.getEncoding()));
		
		RecordTest.doRecordTests(b1);
	}

	@Test
	public void testTransactions() throws BadSignatureException {
		AKeyPair kp = TestState.HERO_KP;

		ATransaction t = Transfer.create(Init.HERO,0, Init.VILLAIN, 1000);
		SignedData<ATransaction> st = kp.signData(t);

		long ts = System.currentTimeMillis();
		Block b = Block.create(ts, TestState.FIRST_PEER_KEY,Vectors.of(st));
		assertEquals(1, b.length());
		assertEquals(t, b.getTransactions().get(0).getValue());
		
		RecordTest.doRecordTests(b);

	}
}
