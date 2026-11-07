package convex.lattice;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Vectors;

/**
 * A write through a cursor whose keys do not fit the data: the container for a key
 * is absent, or cannot hold it. Every cursor path write refuses this way, through
 * {@link LatticeOps#assocExisting} or {@link LatticeOps#assocIn}, as does a write
 * through a JSON value component whose key no longer fits its container's kind.
 *
 * <p>It carries the cursor's keys from the first that does not fit, and that key's
 * depth among them. A resolved component keeps only its keys, so these are what it
 * reports: for a literal path the caller's segments, for a JSON path the keys
 * resolution selected.</p>
 */
public class PathWriteException extends LatticePathException {
	private static final long serialVersionUID = 1L;

	private final transient AVector<ACell> keys;

	/**
	 * @param reason Why the key at {@code depth} cannot be written
	 * @param keys Keys of the write, which are copied
	 * @param depth Index of the first key that does not fit
	 */
	public PathWriteException(String reason,ACell[] keys,int depth) {
		super(reason+" (key "+depth+" of "+Vectors.create(keys)+")",depth);
		this.keys=Vectors.create(keys,depth,keys.length-depth);
	}

	/** The cursor's keys from the first that does not fit. */
	public AVector<ACell> getKeys() {
		return keys;
	}

	/** The first key that does not fit. */
	public ACell getKey() {
		return keys.get(0);
	}
}
