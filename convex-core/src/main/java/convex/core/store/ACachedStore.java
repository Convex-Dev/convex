package convex.core.store;

import convex.core.cvm.CVMEncoder;
import convex.core.data.ACell;
import convex.core.data.AEncoder;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.exceptions.BadFormatException;

/**
 * Abstract base class for stores implementing an in-memory cache of cells
 */
public abstract class ACachedStore extends AStore {

	protected final RefCache refCache=RefCache.create(10000);

	/**
	 * Store-bound encoder. Manages thread-local store context during decode.
	 */
	protected final CVMEncoder encoder=new CVMEncoder(this);

	@Override
	public AEncoder<ACell> getEncoder() {
		return encoder;
	}

	@Override
	@SuppressWarnings("unchecked")
	public final <T extends ACell> T decode(Blob encoding) throws BadFormatException {
		Hash hash=encoding.getContentHash();
		Ref<?> cached= refCache.getCell(hash);
		if (cached!=null) return (T) cached.getValue();

		// Encoder manages thread-local store context
		ACell decoded = encoder.decode(encoding);
		refCache.putCell(decoded);
		return (T)decoded;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> Ref<T> checkCache(Hash h) {
		return (Ref<T>) refCache.getCell(h);
	}
}
