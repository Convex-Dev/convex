package convex.core.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.ByteBuffer;

import org.junit.runner.RunWith;

import com.pholser.junit.quickcheck.From;
import com.pholser.junit.quickcheck.Property;
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck;

import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.exceptions.BadFormatException;
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
	
	// @When(seed = -1436059931257127601l)
	// @From(ValueGen.class)
	@Property
	public void testCAD3Message(@From(ValueGen.class) ACell a) throws BadFormatException {
		// Any CAD3 object should encode as a complete message
		Message m=Message.create(null,a);
		
		MessageType mtype=m.getType();
		assertNotNull(mtype);
		
		Blob enc=m.getMessageData();
		Message m2=Message.create(enc);
		assertEquals(mtype,m2.getType());
		
		assertEquals(a,m2.getPayload());
	}
}
