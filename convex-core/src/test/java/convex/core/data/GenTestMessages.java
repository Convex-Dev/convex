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
	public void messageLengthVLC(Long a) throws BadFormatException {
		ByteBuffer bb = ByteBuffer.allocate(Format.MAX_VLQ_LONG_LENGTH);
		Format.writeVLQLong(bb, a);
		bb.flip();
		assertEquals(bb.remaining(), Format.getVLQLongLength(a));
	}
}
