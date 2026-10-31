package convex.core.data.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;

import convex.core.data.ABlob;

/**
 * Read-only channel over one Blob.
 *
 * The Blob is immutable, so the channel reads a single version without locking.
 * Setting the position costs nothing, and each read walks the tree to the bytes
 * requested, loading branches from the store as needed. Reading a range never
 * touches the chunks before it.
 *
 * Branches missing from the store throw MissingDataException, as on any other
 * Blob access.
 */
public final class BlobChannel implements SeekableByteChannel {

	private final ABlob blob;
	private long position=0;
	private volatile boolean isOpen=true;

	private BlobChannel(ABlob blob) {
		this.blob=blob;
	}

	/**
	 * Creates a channel positioned at the start of a Blob
	 * @param blob Blob to read
	 * @return New channel
	 */
	public static BlobChannel create(ABlob blob) {
		if (blob==null) throw new IllegalArgumentException("Null blob");
		return new BlobChannel(blob);
	}

	@Override
	public synchronized int read(ByteBuffer dst) throws IOException {
		checkOpen();
		if (position>=blob.count()) return -1;
		int read=blob.toByteBuffer(position,dst);
		position+=read;
		return read;
	}

	@Override
	public int write(ByteBuffer src) throws IOException {
		throw new NonWritableChannelException();
	}

	@Override
	public synchronized long position() throws IOException {
		checkOpen();
		return position;
	}

	@Override
	public synchronized SeekableByteChannel position(long newPosition) throws IOException {
		checkOpen();
		if (newPosition<0) throw new IllegalArgumentException("Negative position");
		position=newPosition;
		return this;
	}

	@Override
	public long size() throws IOException {
		checkOpen();
		return blob.count();
	}

	@Override
	public SeekableByteChannel truncate(long size) throws IOException {
		throw new NonWritableChannelException();
	}

	@Override
	public boolean isOpen() {
		return isOpen;
	}

	@Override
	public void close() {
		isOpen=false;
	}

	private void checkOpen() throws ClosedChannelException {
		if (!isOpen) throw new ClosedChannelException();
	}
}
