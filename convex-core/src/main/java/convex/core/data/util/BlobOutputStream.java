package convex.core.data.util;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

import convex.core.data.ABlob;
import convex.core.data.Cells;
import convex.core.store.AStore;

/**
 * Output stream that builds a canonical Blob.
 *
 * With a store, the completed chunks are saved there every interval bytes, so
 * heap use stays near one interval plus the tree's spine whatever the content
 * size. Saved branches are held by soft references, so a store on disk lets the
 * heap free them. Without a store, the Blob is built entirely in memory.
 *
 * Closing has no effect: the Blob can be taken after close.
 */
public final class BlobOutputStream extends OutputStream {

	/** Bytes written between saves, unless a caller chooses otherwise */
	public static final long DEFAULT_PERSIST_INTERVAL=1L<<20;

	private final BlobBuilder builder=new BlobBuilder();
	private final AStore store;
	private final long interval;
	private long unsaved=0;

	private BlobOutputStream(AStore store, long interval) {
		this.store=store;
		this.interval=interval;
	}

	/**
	 * Creates a stream that saves to a store every DEFAULT_PERSIST_INTERVAL bytes
	 * @param store Store to save to, or null to build in memory
	 * @return New stream
	 */
	public static BlobOutputStream create(AStore store) {
		return create(store,DEFAULT_PERSIST_INTERVAL);
	}

	/**
	 * Creates a stream that saves to a store at the given interval
	 * @param store Store to save to, or null to build in memory
	 * @param interval Bytes written between saves
	 * @return New stream
	 */
	public static BlobOutputStream create(AStore store, long interval) {
		if (interval<=0) throw new IllegalArgumentException("Interval must be positive: "+interval);
		return new BlobOutputStream(store,(store==null)?Long.MAX_VALUE:interval);
	}

	@Override
	public void write(int b) throws IOException {
		builder.append((byte)b);
		wrote(1);
	}

	@Override
	public void write(byte[] b, int off, int len) throws IOException {
		Objects.checkFromIndexSize(off,len,b.length);
		while (len>0) {
			int n=(int)Math.min(len,interval-unsaved);
			builder.append(b,off,n);
			off+=n;
			len-=n;
			wrote(n);
		}
	}

	private void wrote(int n) throws IOException {
		unsaved+=n;
		if (unsaved>=interval) {
			builder.persist(store);
			unsaved=0;
		}
	}

	/**
	 * @return Number of bytes written so far
	 */
	public long count() {
		return builder.count();
	}

	/**
	 * Gets the Blob written so far. With a store, the Blob is saved there first,
	 * so none of it needs to stay in memory.
	 * @return Canonical Blob
	 * @throws IOException In case of store error
	 */
	public ABlob toBlob() throws IOException {
		ABlob blob=builder.toBlob();
		return (store==null)?blob:Cells.persist(blob,store);
	}
}
