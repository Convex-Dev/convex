package convex.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import convex.core.data.Format;

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.*;

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
import convex.core.store.Stores;
import convex.core.cpos.CPoSConstants;

public class MessageTest {
	static Hash BAD_HASH=Hash.EMPTY_HASH;
	static AKeyPair KP=AKeyPair.createSeeded(1567856);

	@Test public void testMissingResponse() throws BadFormatException, IOException {
		// non-embedded Blob which might be a missing branch
		Blob b=Blobs.createRandom(400);
		
		Message mq=Message.createDataResponse(CVMLong.ONE, b);
		assertEquals(MessageType.DATA,mq.getType());
		doMessageTest(mq);
		
		Blob enc=mq.getMessageData();
		
		Message mr=Message.create(enc);
		AVector<ACell> v=mr.getPayload();
		
		assertEquals(b,v.get(2));
		Cells.persist(b);
		
		Message md=Message.createDataRequest(CVMLong.ONE, b.getHash());
		doMessageTest(md);
		
		Message mdr=md.makeDataResponse(Stores.current());
		assertEquals(mq,mdr);
		doMessageTest(mdr);
	}
	
	@Test public void testBigMissingResponse() throws BadFormatException, IOException {
		// non-embedded Blob which might be a missing branch
		Blob b=Blobs.createRandom(400);
		Hash[] hashes=new Hash[CPoSConstants.MISSING_LIMIT];
		Arrays.fill(hashes,b.getHash());
		
		Message mr=Message.createDataRequest(b,hashes);
		assertEquals(MessageType.REQUEST_DATA,mr.getType());
		doMessageTest(mr);
		
		Blob enc=mr.getMessageData();
		
		Message mrs=Message.create(enc);
		AVector<ACell> v=mrs.getPayload();
		assertEquals(2+CPoSConstants.MISSING_LIMIT,v.count());
		assertEquals(MessageType.REQUEST_DATA,mrs.getType());
		assertEquals(MessageTag.DATA_QUERY,v.get(0));
		doMessageTest(mrs);
	}
	
	
	
	@Test public void testLostMissingResponse() throws BadFormatException, IOException {
		Message md=Message.createDataRequest(CVMLong.ONE, BAD_HASH);
		assertEquals(Vectors.of(MessageTag.DATA_QUERY,1,BAD_HASH),md.getPayload());
		
		Message mdr=md.makeDataResponse(Stores.current());
		assertEquals(Vectors.of(MessageTag.DATA_RESPONSE,1,null),mdr.getPayload());
	}
	
	@Test
	public void testTypes() throws BadFormatException {
		MessageType[] types = MessageType.values();

		for (MessageType t : types) {
			assertSame(t, MessageType.decode(t.getMessageCode()));
		}
	}
	
	@Test public void testQuery() {
		Message m=Message.createQuery(0, Symbols.STAR_BALANCE, Address.ZERO);
		doMessageTest(m);
	}
	
	@Test public void testTransact() {
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
		Cells.persist(b);
		
		Message m=Message.createDataRequest(CVMLong.ONE, b.getHash());
		Message r=Message.createDataResponse(CVMLong.ONE, b);
		
		assertEquals(r,m.makeDataResponse(Stores.current()));
		
		AVector<ACell> v=r.getPayload();
		assertEquals(CVMLong.ONE,v.get(1));
		assertEquals(b,v.get(2));
		doMessageTest(m);
		doMessageTest(r);
	}
	
	@Test
	public void testStatusMessage() {
		Message m=Message.createStatusRequest(2);
		assertEquals(RT.cvm(2),m.getID());
		doMessageTest(m);
	}
	
	/**
	 * Generic tests for any valid message
	 * @param m
	 */
	public void doMessageTest(Message m) {
		MessageType type=m.getType();
		ACell id=m.getID();
		try {
			ACell payload=m.getPayload();
			assertNotNull(type);
			
			Blob data=m.getMessageData();
			assertTrue(data.count()>0);
		
			ACell dp=Format.decodeMultiCell(data);
			Message m2=Message.create(null, dp);
			
			assertEquals(type,m2.getType());
			assertEquals(id,m2.getID());
			assertEquals(payload,m2.getPayload());
		} catch (BadFormatException e) {
			fail("BAd format: "+m,e);
		}
	}
}
