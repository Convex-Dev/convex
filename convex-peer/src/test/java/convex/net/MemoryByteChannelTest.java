package convex.net;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;

import org.junit.jupiter.api.Test;

public class MemoryByteChannelTest {

	@Test
	public void testEmpty() throws ClosedChannelException {
		MemoryByteChannel mc=MemoryByteChannel.create(1000);
		
		ByteBuffer bb=ByteBuffer.allocate(100);
		int numRead=mc.read(bb);
		assertEquals(0,numRead);
		
		numRead=mc.read(bb);
		assertEquals(0,numRead);
	}
}
