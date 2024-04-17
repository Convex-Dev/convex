package convex.core.store;

import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.exceptions.BadFormatException;

public abstract class ACachedStore extends AStore {
	
	protected final RefCache refCache=RefCache.create(10000);

	@Override
	@SuppressWarnings("unchecked")
	public final <T extends ACell> T decode(Blob encoding) throws BadFormatException {
		Hash hash=encoding.getContentHash();
		Ref<?> cached= refCache.getCell(hash);
		if (cached!=null) return (T) cached.getValue();
		
		// Need to ensure we are reading with the current store set
		AStore tempStore=Stores.current();
		ACell decoded;
		if (tempStore==this) {
			decoded = decodeImpl(encoding);
		} else try {
			Stores.setCurrent(this);
			decoded = decodeImpl(encoding);
		} finally {
			Stores.setCurrent(tempStore);
		}
		refCache.putCell(decoded);
		return (T)decoded;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> Ref<T> checkCache(Hash h) {
		return (Ref<T>) refCache.getCell(h);
	}
}
