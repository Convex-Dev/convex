package convex.core.store;

import java.lang.ref.SoftReference;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.Blob;

/**
 * In-memory cache for Blob decoding. Should be used in the context of a specific Store
 */
public final class BlobCache {

	private SoftReference<ACell>[] cache;
	private int size;
	
	@SuppressWarnings("unchecked")
	private BlobCache(int size) {
		this.size=size;
		this.cache=new SoftReference[size];
	};
	
	public static BlobCache create(int size) {
		return new BlobCache(size);
	}
	
	int getSize() {
		return size;
	}
	
	/**
	 * Gets the Cached Cell for a given Blob Encoding, or null if not cached.
	 * @param encoding Encoding of Cell to look up in cache
	 * @return Cached Cell, or null if not found
	 */
	public ACell getCell(Blob encoding) {
		int ix=calcIndex(encoding);
		SoftReference<ACell> ref=cache[ix];
		if (ref==null) return null;
		ACell cell=ref.get();
		if (cell!=null) {
			if (encoding.equals(cell.getEncoding())) {
				return cell;
			}
			return null; // cached value not the same as this encoding
		}
		cache[ix]=null;
		return null;
	}
	
	/**
	 * Stores a cell in the cache
	 * @param cell Cell to store
	 */
	public void putCell(ACell cell) {
		int ix=calcIndex(cell.getEncoding());
		cache[ix]=new SoftReference<>(cell);
	}

	private int calcIndex(ABlob encoding) {
		int hash=Long.hashCode(encoding.getContentHash().longValue());
		int ix=Math.floorMod(hash, size);
		return ix;
	}


}
