package convex.etch;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Objects;

/**
 * Plaintext byte access to an Etch file, as header reading needs it.
 */
interface EtchHeaderSource {
	/**
	 * @return number of readable bytes
	 */
	long length();

	/**
	 * Reads {@code count} plaintext bytes starting at {@code position}.
	 *
	 * @throws IOException if the range cannot be read in full
	 */
	void read(long position, byte[] destination, int offset, int count) throws IOException;

	/**
	 * Returns a source that reads a channel with positional reads, never
	 * mapping it. Its length is the channel size when this is called.
	 *
	 * @param channel open, readable channel, which the caller keeps and closes
	 * @return header source over the channel
	 * @throws IOException if the channel size cannot be read
	 */
	static EtchHeaderSource of(FileChannel channel) throws IOException {
		Objects.requireNonNull(channel,"channel");
		long size=channel.size();
		return new EtchHeaderSource() {
			@Override
			public long length() {
				return size;
			}

			@Override
			public void read(long position, byte[] destination, int offset, int count)
					throws IOException {
				Objects.checkFromIndexSize(offset,count,destination.length);
				if ((position<0L)||(position>size-count)) {
					throw new EOFException("Etch header read outside the file: position="
							+position+" count="+count+" length="+size);
				}
				ByteBuffer buffer=ByteBuffer.wrap(destination,offset,count);
				while (buffer.hasRemaining()) {
					int n=channel.read(buffer,position+(buffer.position()-offset));
					if (n<0) throw new EOFException("Etch file ended during header read at "
							+(position+(buffer.position()-offset)));
				}
			}
		};
	}
}
