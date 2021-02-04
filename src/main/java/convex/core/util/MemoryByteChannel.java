package convex.core.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.ClosedChannelException;

/**
 * ByteChannel implementation wrapping a fixed size in-memory buffer
 * 
 *
 */
public class MemoryByteChannel implements ByteChannel {
	private final ByteBuffer memory;
	boolean open=true;
	
	private MemoryByteChannel(ByteBuffer buf) {
		this.memory=buf;
	}
	
	public static MemoryByteChannel create(int length) {
		return new MemoryByteChannel(ByteBuffer.allocate(length));
	}
	
	@Override
	public int read(ByteBuffer dst) throws IOException {
		if (!open) throw new ClosedChannelException();
		memory.flip();
		int savledLimit=memory.limit();
		memory.limit(dst.remaining());
		dst.put(memory);
		int numRead=memory.position(); // number of bytes read into dst
		memory.limit(savledLimit);
		memory.compact();
		return numRead;
	}

	@Override
	public boolean isOpen() {
		return open;
	}

	@Override
	public void close() throws IOException {
		open=false;
	}

	@Override
	public int write(ByteBuffer src) throws IOException {
		if (!open) throw new ClosedChannelException();
		int pos=memory.position();
		memory.put(src);
		int numPut=memory.position()-pos;
		return numPut;
	}

}
