package convex.core.store;

import convex.core.data.ACell;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.data.RefSoft;

/**
 * In-memory cache for Blob decoding. Should be used in the context of a specific Store
 */
public final class BlobCache {

	private Ref<?>[] cache;
	private int size;
	
	private BlobCache(int size) {
		this.size=size;
		this.cache=new Ref[size];
	};
	
	public static BlobCache create(int size) {
		return new BlobCache(size);
	}
	
	int getSize() {
		return size;
	}
	
	/**
	 * Gets the Cached Ref for a given hash, or null if not cached.
	 * @param hash Hash of Cell to look up in cache
	 * @return Cached Ref, or null if not found
	 */
	public Ref<?> getCell(Hash hash) {
		int ix=calcIndex(hash);
		Ref<?> ref=cache[ix];
		if (ref==null) return null;
		if (ref instanceof RefSoft) {
			if (!((RefSoft<?>)ref).hasReference()) {
				// Ref is missing, so kill in cache
				cache[ix]=null;
				return null;			
			}
		}
	
		if (ref.getHash().equals(hash)) return ref;
		return null; // different hash, hence not in cache
	}
	
	/**
	 * Stores a Ref in the cache
	 * @param cell Cell with Ref to store
	 */
	public void putCell(ACell cell) {
		Ref<?> ref=Ref.get(cell);
		putCell(ref);
	}
	
	/**
	 * Stores a Ref in the cache
	 * @param ref Ref to store
	 */
	public void putCell(Ref<?> ref) {
		int ix=calcIndex(ref.getHash());
		cache[ix]=ref;
	}

	private int calcIndex(Hash h) {
		int hash=(int)h.longValue();
		int ix=Math.floorMod(hash, size);
		return ix;
	}


}
