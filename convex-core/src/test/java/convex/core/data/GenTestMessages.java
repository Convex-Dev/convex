package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.ByteBuffer;

import org.junit.runner.RunWith;

import com.pholser.junit.quickcheck.From;
import com.pholser.junit.quickcheck.Property;
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck;

import convex.core.exceptions.BadFormatException;
import convex.core.message.Message;
import convex.core.message.MessageType;
import convex.test.generators.ValueGen;

@RunWith(JUnitQuickcheck.class)
public class GenTestMessages {

	@Property
	public void messageLengthVLQ(Integer a) throws BadFormatException {
		if (a<1) return;
		ByteBuffer bb = ByteBuffer.allocate(5); // sufficient for 32 bits
		Format.writeVLQCount(bb, a);
		bb.flip();
		assertEquals(a,Format.peekMessageLength(bb));
		assertEquals(bb.remaining(), Format.getVLQCountLength(a));
	}
	
	@Property
	public void testCAD3Message(@From(ValueGen.class) ACell a) throws BadFormatException {
		Message m=Message.create(null,a);
		
		MessageType mtype=m.getType();
		assertNotNull(mtype);
		
		Blob enc=m.getMessageData();
		Message m2=Message.create(enc);
		assertEquals(mtype,m2.getType());
		
		assertEquals(a,m2.getPayload());
	}
}
