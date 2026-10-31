package convex.core.data.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;

import convex.core.data.ABlob;

/**
 * Read-only channel over one Blob, or a range of it.
 *
 * The Blob is immutable, so the channel reads a single version without locking.
 * Setting the position costs nothing, and each read walks the tree to the bytes
 * requested, loading branches from the store as needed. Reading a range never
 * touches the chunks before it.
 *
 * A channel over a range behaves as a channel over just those bytes: its size is
 * the range length and positions count from the range start.
 *
 * Branches missing from the store throw MissingDataException, as on any other
 * Blob access.
 */
public final class BlobChannel implements SeekableByteChannel {

	private final ABlob blob;
	private final long start;
	private final long size;
	private long position=0;
	private volatile boolean isOpen=true;

	private BlobChannel(ABlob blob, long start, long size) {
		this.blob=blob;
		this.start=start;
		this.size=size;
	}

	/**
	 * Creates a channel positioned at the start of a Blob
	 * @param blob Blob to read
	 * @return New channel
	 */
	public static BlobChannel create(ABlob blob) {
		if (blob==null) throw new IllegalArgumentException("Null blob");
		return create(blob,0,blob.count());
	}

	/**
	 * Creates a channel over a range of a Blob, positioned at the range start
	 * @param blob Blob to read
	 * @param start Start of range, inclusive
	 * @param end End of range, exclusive
	 * @return New channel
	 */
	public static BlobChannel create(ABlob blob, long start, long end) {
		if (blob==null) throw new IllegalArgumentException("Null blob");
		if ((start<0)||(end<start)||(end>blob.count())) {
			throw new IllegalArgumentException("Invalid range "+start+"-"+end+" in blob of size "+blob.count());
		}
		return new BlobChannel(blob,start,end-start);
	}

	@Override
	public synchronized int read(ByteBuffer dst) throws IOException {
		checkOpen();
		if (position>=size) return -1;
		long n=Math.min(dst.remaining(),size-position);
		int read=blob.toByteBuffer(start+position,n,dst);
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
		return size;
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
