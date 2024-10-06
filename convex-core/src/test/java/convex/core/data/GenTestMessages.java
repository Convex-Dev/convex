package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;

import org.junit.runner.RunWith;

import com.pholser.junit.quickcheck.Property;
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck;

import convex.core.exceptions.BadFormatException;

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
}
