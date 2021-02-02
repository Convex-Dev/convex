package etch;

import java.io.File;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.logging.Logger;

import convex.core.crypto.Hash;
import convex.core.data.ACell;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
import convex.core.store.AStore;
import convex.core.store.Stores;
import convex.core.util.Utils;

/**
 * Class implementing on-disk memory-mapped storage of Convex data.
 * 
 * 
 * "There are only two hard things in Computer Science: cache invalidation and
 * naming things." - Phil Karlton
 * 
 * Objects are keyed by cryptographic hash. That solves naming. Objects are
 * immutable. That solves cache invalidation.
 * 
 * Garbage collection is left as an exercise for the reader.
 */
public class EtchStore extends AStore {
	private static final Logger log = Logger.getLogger(EtchStore.class.getName());

	/**
	 * Etch Storage of persisted Refs for each hash value
	 */
	private Etch etch;

	public EtchStore(Etch etch) {
		this.etch = etch;
	}

	/**
	 * Creates an EtchStore using a specified file. 
	 * 
	 * @param file File to use for storage. Will be created it it does not already exist.
	 * @return EtchStore instance
	 */
	public static EtchStore create(File file) throws IOException {
		Etch etch = Etch.create(file);
		return new EtchStore(etch);
	}

	/**
	 * Create an Etch store using a new temporary file with the given prefix
	 * 
	 * @return New EtchStore instance
	 */
	public static EtchStore createTemp(String prefix) {
		try {
			Etch etch = Etch.createTempEtch(prefix);
			return new EtchStore(etch);
		} catch (IOException e) {
			throw Utils.sneakyThrow(e);
		}
	}
	
	/**
	 * Create an Etch store using a new temporary file with a generated prefix
	 * 
	 * @return New EtchStore instance
	 */
	public static EtchStore createTemp() {
		try {
			Etch etch = Etch.createTempEtch();
			return new EtchStore(etch);
		} catch (IOException e) {
			throw Utils.sneakyThrow(e);
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends ACell> Ref<T> refForHash(Hash hash) {
		try {
			Ref<ACell> existing = etch.read(hash);
			if (existing == null) return null;
			return (Ref<T>) existing;
		} catch (IOException e) {
			throw new Error("IO exception from Etch", e);
		}
	}
	
	@Override
	public <T extends ACell> Ref<T> persistRef(Ref<T> ref, Consumer<Ref<ACell>> noveltyHandler) {
		return persistRef(ref,noveltyHandler,Ref.PERSISTED);
	}
	
	@Override
	public <T extends ACell> Ref<T> announceRef(Ref<T> ref, Consumer<Ref<ACell>> noveltyHandler) {
		return persistRef(ref,noveltyHandler,Ref.ANNOUNCED);
	}

	@SuppressWarnings("unchecked")
	public <T extends ACell> Ref<T> persistRef(Ref<T> ref, Consumer<Ref<ACell>> noveltyHandler, int requiredStatus) {
		// first check if the Ref is already persisted to required level
		if (ref.getStatus()>=requiredStatus) return ref;
		
		final T o=ref.getValue();
		
		if (o instanceof ACell) {
			ACell cell=(ACell)o;
			
			// check store for existing ref first. 
			boolean embedded=ref.isEmbedded();
			Hash hash =null;
			if (!embedded) {;
				hash = ref.getHash();
				Ref<T> existing = refForHash(hash);
				if (existing != null) {
					// Return existing ref if status is sufficient
					if (existing.getStatus()>=requiredStatus) return existing;
				}
			}
			
			// beyond STORED level, need to recursively persist child refs
			if (requiredStatus>Ref.STORED) {
				IRefFunction func=r -> {
					return persistRef((Ref<ACell>)r,noveltyHandler,requiredStatus);
				};
			
				// need to do recursive persistence
				// TODO: maybe switch to a queue? Mitigate risk of stack overflow?
				ACell newObject = ((ACell) o).updateRefs(func);
		
				// perhaps need to update Ref 
				if (cell!=newObject) ref=ref.withValue((T)newObject);
			}
			
			if (!embedded) {
				final Hash fHash=hash;
				log.log(Stores.PERSIST_LOG_LEVEL,()->"Etch persisting at status="+requiredStatus+" hash = 0x"+fHash.toHexString()+" ref of class "+Utils.getClassName(o)+" with store "+this);

				Ref<ACell> result;
				try {
					// ensure status is set when we write to store
					ref=ref.withMinimumStatus(requiredStatus);
					result=etch.write(hash, (Ref<ACell>) ref);
				} catch (IOException e) {
					throw Utils.sneakyThrow(e);
				}

				// call novelty handler if newly persisted
				if (noveltyHandler != null) noveltyHandler.accept(result);
			}
		}
		return ref.withMinimumStatus(requiredStatus);
	}

	@Override
	public <T extends ACell> Ref<T> storeRef(Ref<T> ref, Consumer<Ref<ACell>> noveltyHandler) {
		return persistRef(ref,noveltyHandler,Ref.STORED);
	}
	
	@Override
	public String toString() {
		return "EtchStore at: "+etch.getFile().getName();
	}

	/**
	 * Gets the database file name for this EtchStore
	 * @return File name as a String
	 */
	public String getFileName() {
		return etch.getFile().toString();
	}

	public void close() {
		etch.close();
	}

	public File getFile() {
		return etch.getFile();
	}

	@Override
	public Hash getRootHash() throws IOException {
		return etch.getRootHash();
	}
	
	@Override
	public void setRootHash(Hash h) throws IOException {
		etch.setRootHash(h);
	}
}
