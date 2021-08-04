package convex.net;

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
	/**
	 * ByteBuffer for channel contents. 
	 * Maintained ready for writing
	 */
	private final ByteBuffer memory;
	boolean open=true;
	
	private MemoryByteChannel(ByteBuffer buf) {
		this.memory=buf;
	}
	
	public static MemoryByteChannel create(int length) {
		ByteBuffer bb=ByteBuffer.allocate(length);
		return new MemoryByteChannel(bb);
	}
	
	@Override
	public int read(ByteBuffer dst) throws ClosedChannelException  {
		if (!open) throw new ClosedChannelException();
		synchronized (memory) {
			memory.flip(); // position will be 0, limit is available bytes
			int available=memory.remaining();
			int numRead=Math.min(available, dst.remaining());
			memory.limit(numRead);
			dst.put(memory);
			memory.limit(available);
			memory.compact();
			return numRead;
		}
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
		synchronized(memory) {
			synchronized(src) {
				int numPut=Math.min(memory.remaining(), src.remaining());
				int savedLimit=src.limit();
				src.limit(src.position()+numPut);
				memory.put(src);
				src.limit(savedLimit);
				return numPut;
			}
		}
	}

}
