package etch;

import java.io.File;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.logging.Logger;

import convex.core.crypto.Hash;
import convex.core.data.ACell;
import convex.core.data.IRefContainer;
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
	public <T> Ref<T> refForHash(Hash hash) {
		try {
			Ref<ACell> existing = etch.read(hash);
			if (existing == null) return null;
			return (Ref<T>) existing;
		} catch (IOException e) {
			throw new Error("IO exception from Etch", e);
		}
	}
	
	@Override
	public Ref<ACell> persistRef(Ref<ACell> ref, Consumer<Ref<ACell>> noveltyHandler) {
		return persistRef(ref,noveltyHandler,Ref.PERSISTED);
	}
	
	@Override
	public Ref<ACell> announceRef(Ref<ACell> ref, Consumer<Ref<ACell>> noveltyHandler) {
		return persistRef(ref,noveltyHandler,Ref.ANNOUNCED);
	}

	@SuppressWarnings("unchecked")
	public Ref<ACell> persistRef(Ref<ACell> ref, Consumer<Ref<ACell>> noveltyHandler, int requiredStatus) {
		// check store for existing ref first. Return this is we have it
		if (ref.isEmbedded()) return ref;
		Hash hash = ref.getHash();
		Ref<ACell> existing = refForHash(hash);
		if (existing != null) {
			if (existing.getStatus()>=requiredStatus) return existing;
		}

		ACell o = ref.getValue();
		if (o instanceof IRefContainer) {
			// Function to update Refs
			IRefFunction func=r -> {
				// Go via persist, since needs to check if Ref should be persisted at all
				return persistRef(r,noveltyHandler,requiredStatus);
			};
			
			// need to do recursive persistence
			ACell newObject = ((IRefContainer) o).updateRefs(func);
			
			// perhaps need to update Ref 
			if (o!=newObject) ref=ref.withValue(newObject);
		}
		
		log.log(Stores.PERSIST_LOG_LEVEL,()->"Etch persisting at status="+requiredStatus+" ref "+hash.toHexString()+" of class "+Utils.getClassName(o)+" with store "+this);

		Ref<ACell> result;
		try {
			// ensure status is PERSISTED when we write to store
			ref=ref.withMinimumStatus(requiredStatus);
			result=etch.write(hash, ref);
		} catch (IOException e) {
			throw new Error("IO exception from Etch", e);
		}

		// call novelty handler if newly persisted
		if (noveltyHandler != null) noveltyHandler.accept(result);
		return result;
	}

	@Override
	public Ref<ACell> storeRef(Ref<ACell> ref, Consumer<Ref<ACell>> noveltyHandler) {
		// check store for existing ref first. Return this is we have it
		Hash hash = ref.getHash();
		Ref<ACell> existing = refForHash(hash);
		if (existing != null) return existing; // already must be STORED at minimum
 
		log.log(Stores.STORE_LOG_LEVEL,()-> "Etch storing at status=1 ref "+hash.toHexString()+" with store "+this);

		
		Ref<ACell> result;
		try {
			result=etch.write(hash, ref.withMinimumStatus(Ref.STORED));
		} catch (IOException e) {
			throw new Error("IO exception from Etch", e);
		}
		
		// call novelty handler if newly stored
		if (noveltyHandler != null) noveltyHandler.accept(result);
		
		return result;
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
}
