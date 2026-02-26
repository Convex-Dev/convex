package convex.core.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
		Message m=Message.create((MessageType)null,Reader.read("#[e645]"));
		doMessageTest(m);
	}
	
	@Test public void testStorelessTransactDecode() throws BadFormatException {
		// Create a transaction message with CVM-specific Invoke type
		ATransaction tx=Invoke.create(Address.create(42), 1, Reader.read("(+ 1 2)"));
		SignedData<ATransaction> stx=KP.signData(tx);
		Message m=Message.createTransaction(7, stx);

		// Encode to wire format
		Blob data=m.getMessageData();
		assertTrue(data.count()>0);

		// Simulate receiving from network: raw data, no payload, no store
		Message received=Message.create(data);
		assertNull(received.getPayload()); // pure accessor, not yet decoded

		// getRequestID() returns null before decode (no crash)
		assertNull(received.getRequestID());

		// Vector-based type is UNKNOWN until payload is decoded
		assertEquals(MessageType.UNKNOWN,received.getType());

		// Storeless decode via getPayload(null) — complete message, all branches present
		ACell payload=received.getPayload(null);
		assertNotNull(payload);
		assertEquals(m.getPayload(),payload);

		// After decode, type and IDs are available
		assertEquals(MessageType.TRANSACT,received.getType());
		assertEquals(RT.cvm(7),received.getRequestID());
		assertEquals(RT.cvm(7),received.getID());

		// Verify the CVM Invoke transaction survived the round-trip
		AVector<?> v=RT.ensureVector(payload);
		assertNotNull(v);
		assertEquals(MessageTag.TRANSACT,v.get(0));
		SignedData<?> decodedStx=(SignedData<?>)v.get(2);
		ATransaction decodedTx=(ATransaction)decodedStx.getValue();
		assertTrue(decodedTx instanceof Invoke);
		assertEquals(Address.create(42),decodedTx.getOrigin());
	}

	@Test public void testStorelessResultDecode() throws BadFormatException {
		// Create a Result message
		Result res=Result.create(CVMLong.create(5), Reader.read("42"), null);
		Message m=Message.createResult(res);

		// Encode and simulate network receive
		Blob data=m.getMessageData();
		Message received=Message.create(data);

		// Result type and ID inferrable from raw tag byte (no decode needed)
		assertEquals(MessageType.RESULT,received.getType());
		assertEquals(RT.cvm(5),received.getResultID());
		assertEquals(RT.cvm(5),received.getID());

		// Storeless decode via getPayload(null)
		Result decoded=received.getPayload(null);
		assertNotNull(decoded);
		assertEquals(res,decoded);
		assertEquals(RT.cvm(42),decoded.getValue());
	}

	@Test public void testStorelessQueryDecode() throws BadFormatException {
		Message m=Message.createQuery(3, "(+ 1 2)", Address.create(12));

		Blob data=m.getMessageData();
		Message received=Message.create(data);

		// Storeless decode via getPayload(null) recovers full payload
		ACell payload=received.getPayload(null);
		assertEquals(m.getPayload(),payload);
		assertEquals(MessageType.QUERY,received.getType());
		assertEquals(RT.cvm(3),received.getID());
	}

	// ---- Connection integration tests ----

	@Test public void testLocalConnection() {
		// LocalConnection wraps a predicate and delivers via sendMessage
		java.util.concurrent.atomic.AtomicReference<Message> received = new java.util.concurrent.atomic.AtomicReference<>();
		LocalConnection conn = new LocalConnection(m -> {
			received.set(m);
			return true;
		});

		assertNull(conn.getRemoteAddress());
		assertFalse(conn.isClosed());
		assertFalse(conn.isTrusted());
		assertEquals(0, conn.getReceivedCount());

		Message result = Message.createResult(Result.create(CVMLong.ONE, CVMLong.create(42), null));
		assertTrue(conn.trySendMessage(result));
		assertSame(result, received.get());

		// close is a no-op
		conn.close();
		assertFalse(conn.isClosed());
	}

	@Test public void testMessageWithConnection() throws BadFormatException {
		// Message.withConnection() sets the connection for return routing
		java.util.concurrent.atomic.AtomicReference<Message> received = new java.util.concurrent.atomic.AtomicReference<>();
		LocalConnection conn = new LocalConnection(m -> {
			received.set(m);
			return true;
		});

		Message m = Message.createQuery(1, "(+ 1 2)", Address.ZERO);
		assertNull(m.getConnection());

		Message mc = m.withConnection(conn);
		assertSame(conn, mc.getConnection());
		assertSame(mc, mc.withConnection(conn)); // same connection, same message

		// returnResult should route through the connection
		Result r = Result.create(CVMLong.ONE, CVMLong.create(3), null);
		mc.returnResult(r);
		assertNotNull(received.get());
		assertEquals(MessageType.RESULT, received.get().getType());
	}

	@Test public void testReturnMessagePrefersConnection() {
		// When both connection and handler are set, connection takes priority
		java.util.concurrent.atomic.AtomicBoolean connUsed = new java.util.concurrent.atomic.AtomicBoolean(false);
		java.util.concurrent.atomic.AtomicBoolean handlerUsed = new java.util.concurrent.atomic.AtomicBoolean(false);

		LocalConnection conn = new LocalConnection(m -> { connUsed.set(true); return true; });

		Message m = Message.createQuery(1, "(+ 1 2)", Address.ZERO);
		Message mc = m.withResultHandler(msg -> { handlerUsed.set(true); return true; })
		             .withConnection(conn);

		Message result = Message.createResult(Result.create(CVMLong.ONE, CVMLong.create(3), null));
		mc.returnMessage(result);

		assertTrue(connUsed.get(), "Connection should be used for return routing");
		assertFalse(handlerUsed.get(), "Handler should not be used when connection is present");
	}

	@Test public void testCloseConnectionActuallyCloses() {
		java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean(false);
		AConnection conn = new AConnection() {
			@Override public boolean sendMessage(Message msg) { return true; }
			@Override public java.net.InetSocketAddress getRemoteAddress() { return null; }
			@Override public boolean isClosed() { return closed.get(); }
			@Override public void close() { closed.set(true); }
			@Override public long getReceivedCount() { return 0; }
		};

		Message m = Message.createQuery(1, "1", Address.ZERO).withConnection(conn);
		assertSame(conn, m.getConnection());

		m.closeConnection();
		assertTrue(closed.get(), "closeConnection() should close the underlying connection");
		assertNull(m.getConnection(), "Connection should be null after close");
	}

	@Test public void testCreateWithConnection() throws BadFormatException {
		// Message.create(AConnection, Blob) creates a message with connection set
		LocalConnection conn = new LocalConnection(m -> true);
		Message m = Message.createQuery(1, "(+ 2 3)", Address.ZERO);
		Blob data = m.getMessageData();

		Message m2 = Message.create(conn, data);
		assertSame(conn, m2.getConnection());

		// Payload should decode correctly
		m2.getPayload(null);
		assertEquals(m.getPayload(), m2.getPayload());
		assertEquals(MessageType.QUERY, m2.getType());
	}

	@Test public void testConnectionTrust() {
		LocalConnection conn = new LocalConnection(m -> true);
		assertFalse(conn.isTrusted());

		conn.setTrustedKey(KP.getAccountKey());
		assertTrue(conn.isTrusted());
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

			// Test store-based decode round-trip
			ACell dp=Samples.TEST_STORE.decodeMultiCell(data);
			Message m2=Message.create((MessageType)null, dp);

			assertEquals(type,m2.getType());
			assertEquals(id,m2.getID());
			assertEquals(payload,m2.getPayload());

			// Test storeless decode round-trip for complete messages.
			// Partial messages (e.g. beliefs with external branches) would throw
			// PartialMessageException — those require getPayload(store) instead.
			if (type!=MessageType.BELIEF) {
				Message m3=Message.create(data);
				ACell storelessPayload=m3.getPayload(null);
				assertEquals(payload,storelessPayload);
				assertEquals(type,m3.getType());
				assertEquals(id,m3.getID());
			}

		} catch (BadFormatException e) {
			fail("Bad format: "+m,e);
		}
	}
}
