package convex.core.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

import convex.core.Result;
import convex.core.cpos.Belief;
import convex.core.cpos.Block;
import convex.core.cpos.CPoSConstants;
import convex.core.cpos.Order;
import convex.core.crypto.AKeyPair;
import convex.core.cvm.Address;
import convex.core.cvm.Symbols;
import convex.core.cvm.transactions.ATransaction;
import convex.core.cvm.transactions.Invoke;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.SignedData;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.store.AStore;
import convex.core.store.Stores;
import convex.test.Samples;

public class MessageTest {

	static Hash BAD_HASH=Hash.EMPTY_HASH;
	static AKeyPair KP=AKeyPair.createSeeded(1567856);

	@Test public void testMissingResponse() throws BadFormatException, IOException {
		// non-embedded Blob which might be a missing branch
		Blob b=Blobs.createRandom(400);

		Message mq=Message.createDataResponse(CVMLong.ONE, b);
		assertEquals(MessageType.RESULT,mq.getType());
		doMessageTest(mq);

		Blob enc=mq.getMessageData();

		// Decoding messages with non-embedded content requires a store
		Message mr=Message.create(enc);
		mr.getPayload(Samples.TEST_STORE);
		AVector<ACell> v=mr.toResult().getValue();

		assertEquals(b,v.get(0));
		Cells.persist(b, Samples.TEST_STORE);

		Message md=Message.createDataRequest(CVMLong.ONE, b.getHash());
		doMessageTest(md);

		Message mdr=md.makeDataResponse(Samples.TEST_STORE);
		assertEquals(mq,mdr);
		doMessageTest(mdr);
	}
	
	@SuppressWarnings("unchecked")
	@Test public void testBeliefMessage() throws BadFormatException {
		long TS=120;
		Block b=Block.of(TS);
		
		Belief belief=Belief.create(KP, Order.create(0,0,KP.signData(b)));
		
		Message m=Message.createBelief(belief);
	
		assertNull(m.getRequestID());
		assertEquals(MessageType.BELIEF,m.getType());
		
		Blob enc=m.getMessageData();
		
		ACell b2=Samples.TEST_STORE.decodeMultiCell(enc);
		assertEquals(belief.getHash(),b2.getHash());
		
		Message m2=Message.create(enc);
		assertNull(m.getResultID());

		assertEquals(MessageType.BELIEF,m2.getType());
		m2.getPayload(Samples.TEST_STORE);
		assertEquals(belief,m2.getPayload());


	}
	
	@Test public void testBigMissingResponse() throws BadFormatException, IOException {
		// non-embedded Blob which might be a missing branch
		Blob b=Blobs.createRandom(400);
		Hash[] hashes=new Hash[CPoSConstants.MISSING_LIMIT];
		Arrays.fill(hashes,b.getHash());

		Message mr=Message.createDataRequest(b,hashes);
		assertEquals(MessageType.DATA_REQUEST,mr.getType());
		doMessageTest(mr);

		Blob enc=mr.getMessageData();

		// Decoding messages with non-embedded content requires a store
		Message mrs=Message.create(enc);
		mrs.getPayload(Samples.TEST_STORE);
		AVector<ACell> v=mrs.getPayload();
		assertEquals(2+CPoSConstants.MISSING_LIMIT,v.count());
		assertEquals(MessageType.DATA_REQUEST,mrs.getType());
		assertEquals(MessageTag.DATA_REQUEST,v.get(0));
		doMessageTest(mrs);
	}
	
	
	
	@Test public void testLostMissingResponse() throws BadFormatException, IOException {
		Message md=Message.createDataRequest(CVMLong.ONE, BAD_HASH);
		assertEquals(Vectors.of(MessageTag.DATA_REQUEST,1,BAD_HASH),md.getPayload());
		
		Message mdr=md.makeDataResponse(Samples.TEST_STORE);
		assertEquals(Result.create(CVMLong.ONE, Vectors.of(Cells.NIL)),mdr.getPayload());
	}
	
	@Test
	public void testTypes() throws BadFormatException {
		MessageType[] types = MessageType.values();

		for (MessageType t : types) {
			assertSame(t, MessageType.decode(t.getMessageCode()));
		}
	}
	
	@Test public void testQuery() throws BadFormatException {
		Message m=Message.createQuery(0, Symbols.STAR_BALANCE, Address.ZERO);
		doMessageTest(m);
	}
	
	@Test public void testTransact() throws BadFormatException {
		ATransaction tx=Invoke.create(Address.create(134564), 124334, Symbols.STAR_BALANCE);
		SignedData<ATransaction> stx=KP.signData(tx);
		Message m=Message.createTransaction(12, stx);
		doMessageTest(m);
	}


	@Test
	public void testBadCode() {
		assertThrows(BadFormatException.class, () -> MessageType.decode(-1));
	}
	
	@Test 
	public void testDataMessages() throws BadFormatException, IOException {
		Blob b=Blob.createRandom(new Random(1256785), 1000);
		Cells.persist(b, Samples.TEST_STORE);

		Message m=Message.createDataRequest(CVMLong.ONE, b.getHash());
		Message r=Message.createDataResponse(CVMLong.ONE, b);

		assertEquals(r,m.makeDataResponse(Samples.TEST_STORE));
		
		// Check Result
		Result res=r.toResult();
		assertEquals(CVMLong.ONE,res.getID());
		
		AVector<ACell> v=res.getValue();
		assertEquals(b,v.get(0));
		doMessageTest(m);
		doMessageTest(r);
	}
	
	@Test
	public void testStatusMessage() throws BadFormatException {
		Message m=Message.createStatusRequest(2);
		assertEquals(RT.cvm(2),m.getID());
		doMessageTest(m);
	}
	
	@Test public void testCoreDefExtension() throws BadFormatException {
		// This was a regression at one point where Local was badly re-encoded
		Message m=Message.create(null,Reader.read("#[e645]"));
		doMessageTest(m);
	}
	
	/**
	 * Generic tests for any valid message
	 * @param m Message to test
	 * @throws BadFormatException 
	 */
	public void doMessageTest(Message m) throws BadFormatException {
		MessageType type=m.getType();
		assertNotNull(type);
		
		ACell id=m.getID();
		ACell reqID=m.getRequestID();
		ACell resultID=m.getResultID();
		if (reqID!=null) {
			assertNull(resultID); // should be both a request and a result
			assertEquals(id,reqID);
		}
		
		if (resultID!=null) {
			assertEquals(MessageType.RESULT,type);
			assertNull(reqID);
			assertEquals(id,resultID);
		}
		
		try {
			ACell payload=m.getPayload();
			
			Blob data=m.getMessageData();
			assertTrue(data.count()>0);
		
			ACell dp=Samples.TEST_STORE.decodeMultiCell(data);
			Message m2=Message.create(null, dp);
			
			assertEquals(type,m2.getType());
			assertEquals(id,m2.getID());
			assertEquals(payload,m2.getPayload());
			
		} catch (BadFormatException e) {
			fail("Bad format: "+m,e);
		}
	}
}
