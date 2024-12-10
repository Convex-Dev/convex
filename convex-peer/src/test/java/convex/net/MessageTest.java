package convex.net;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;

import org.junit.jupiter.api.*;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.store.Stores;

public class MessageTest {
	Hash BAD_HASH=Hash.EMPTY_HASH;

	@Test public void testMissingResponse() throws BadFormatException, IOException {
		// non-embedded Blob which might be a missing branch
		Blob b=Blobs.createRandom(400);
		
		Message mq=Message.createDataResponse(CVMLong.ONE, b);
		assertEquals(MessageType.DATA,mq.getType());
		
		Blob enc=mq.getMessageData();
		
		Message mr=Message.create(enc);
		AVector<ACell> v=mr.getPayload();
		
		assertEquals(b,v.get(1));
		Cells.persist(b);
		
		Message md=Message.createDataRequest(CVMLong.ONE, b.getHash());
		
		Message mdr=md.makeDataResponse(Stores.current());
		assertEquals(mq,mdr);
	}
	
	@Test public void testLostMissingResponse() throws BadFormatException, IOException {
		Message md=Message.createDataRequest(CVMLong.ONE, BAD_HASH);
		assertEquals(Vectors.of(1,BAD_HASH),md.getPayload());
		
		Message mdr=md.makeDataResponse(Stores.current());
		assertEquals(Vectors.of(1,null),mdr.getPayload());
	}
}
