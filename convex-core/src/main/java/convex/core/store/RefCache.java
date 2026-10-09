package convex.core.store;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;

import convex.core.data.ACell;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.data.RefDirect;
import convex.core.data.RefSoft;

/**
 * Fixed-size reference cache owned by one store. Soft refs must belong to that
 * store; direct refs may cache unpersisted values but carry no storage claim.
 * The null singleton is store-independent and is also accepted.
 *
 * <p>Admission checks happen on insertion, never on the hit path. Release/acquire
 * array access publishes newly constructed refs to concurrent readers. Collisions
 * and racing replacements may evict entries: this is a cache, not storage.</p>
 */
public final class RefCache {

	private static final VarHandle ENTRIES=MethodHandles.arrayElementVarHandle(Ref[].class);
	private final Ref<?>[] cache;
	private final int size;
	private final AStore store;
	
	private RefCache(AStore store, int size) {
		if (size<=0) throw new IllegalArgumentException("Reference cache size must be positive: "+size);
		this.store=Objects.requireNonNull(store);
		this.size=size;
		this.cache=new Ref[size];
	}
	
	public static RefCache create(AStore store, int size) {
		return new RefCache(store, size);
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
		Ref<?> ref=(Ref<?>) ENTRIES.getAcquire(cache, ix);
		if ((ref==null)||!ref.getHash().equals(hash)) return null;
		// Leave cleared entries for the next insertion to replace. Clearing here
		// could discard a newer ref published by a concurrent writer.
		if ((ref instanceof RefSoft<?> soft)&&!soft.hasReference()) return null;
		return ref;
	}
	
	/**
	 * Cache a decoded value without importing another store's ref or status.
	 * A shared cell's attached ref may have changed since it was first decoded.
	 * Reuse the existing ref when safe; allocate only when its binding or status
	 * prevents reuse. Does not change the cell's attached ref.
	 */
	<T extends ACell> Ref<T> putDecoded(T cell) {
		Ref<T> ref=Ref.get(cell);
		if (ref instanceof RefSoft<?> soft) {
			if (soft.getStore()!=store) ref=RefDirect.create(cell, ref.getHash(), Ref.UNKNOWN);
		} else if ((ref.getStatus()!=Ref.UNKNOWN)&&(ref!=RefDirect.NULL_VALUE)) {
			ref=ref.withStatus(Ref.UNKNOWN);
		}
		putCell(ref);
		return ref;
	}
	
	/**
	 * Stores a Ref in the cache
	 * @param ref Ref to store
	 * @throws IllegalArgumentException if the ref is foreign or a direct ref
	 * carries an unprovable storage claim
	 */
	public void putCell(Ref<?> ref) {
		if (ref instanceof RefSoft<?> soft) {
			if (soft.getStore()!=store) throw new IllegalArgumentException("Cannot cache a foreign Ref");
		} else if ((ref.getStatus()!=Ref.UNKNOWN)&&(ref!=RefDirect.NULL_VALUE)) {
			throw new IllegalArgumentException("Stored cache refs must be bound to their store");
		}
		int ix=calcIndex(ref.getHash());
		ENTRIES.setRelease(cache, ix, ref);
	}

	private int calcIndex(Hash h) {
		int hash=(int)h.longValue();
		int ix=Math.floorMod(hash, size);
		return ix;
	}


}
