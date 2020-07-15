package etch.store;

import java.io.File;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.logging.Logger;

import convex.core.crypto.Hash;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.IRefContainer;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
import convex.core.data.RefSoft;
import convex.core.exceptions.BadFormatException;
import convex.core.store.AStore;
import convex.core.store.Stores;
import convex.core.util.Utils;
import etch.api.Etch;

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
	public static final File DEFAULT_FILE = new File("etch-db");
	
	/**
	 * Default Etch store instance. Intended for use by servers, but may be used by clients.
	 */
	public static final EtchStore DEFAULT = EtchStore.create(DEFAULT_FILE);
	
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
	public static EtchStore create(File file) {
		try {
			Etch etch = Etch.create(file);
			return new EtchStore(etch);
		} catch (IOException e) {
			throw Utils.sneakyThrow(e);
		}
	}

	/**
	 * Create an Etch store using a new temporary file.
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

	@Override
	public <T> Ref<T> refForHash(Hash hash) {
		Blob b;
		try {
			b = etch.read(hash);
			if (b == null) return null;

			// construct the actual object
			T o = Format.read(b);

			// we can be a bit clever, and re-use the hash / loaded data blob.
			if (o instanceof ACell) {
				b.attachHash(hash);
				((ACell) o).attachBlob(b);
			}

			// create a soft ref. Safe because we know we can always fetch from storage
			// again if evicted.
			// minimum status of PERSISTED, TODO: higher statuses
			Ref<T> ref = RefSoft.create(o, hash, Ref.PERSISTED);
			return ref;
		} catch (IOException e) {
			throw new Error("IO exception from Etch", e);
		} catch (BadFormatException e) {
			throw new Error("Data format exception from Etch", e);
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T> Ref<T> persistRef(Ref<T> ref, Consumer<Ref<T>> noveltyHandler) {
		// check store for existing ref first. Return this is we have it
		Hash hash = ref.getHash();
		Ref<T> existing = refForHash(hash);
		if (existing != null) {
			if (existing.getStatus()>=Ref.PERSISTED) return existing;
			// TODO: think about need to boost level?
		}

		T o = ref.getValue();
		if (o instanceof IRefContainer) {
			// Function to update Refs
			IRefFunction func=r -> {
				// Go via persist, since needs to check if Ref should be persisted at all
				return ((Ref<T>)(r)).persist(noveltyHandler);
			};
			
			// need to do recursive persistence
			T newObject = ((IRefContainer) o).updateRefs(func);
			
			// perhaps need to update Ref 
			if (o!=newObject) ref=ref.withValue(newObject);
		}
		
		log.log(Stores.PERSIST_LEVEL,()->"EtchStore.persistRef: Persisting ref "+hash.toHexString()+" of class "+Utils.getClassName(o)+" with store "+this);

		try {
			T valueToStore=ref.getValue();
			Blob blob=Format.encodedBlob(valueToStore);
			etch.write(hash, blob);
		} catch (IOException e) {
			throw new Error("IO exception from Etch", e);
		}

		if (noveltyHandler != null) noveltyHandler.accept(ref);
		return ref.withMinimumStatus(Ref.PERSISTED);
	}

	@Override
	public <T> Ref<T> storeRef(Ref<T> ref, Consumer<Ref<T>> noveltyHandler) {
		// check store for existing ref first. Return this is we have it
		Hash hash = ref.getHash();
		Ref<T> existing = refForHash(hash);
		if (existing != null) return existing;

		try {
			etch.write(hash, Format.encodedBlob(ref.getValue()));
		} catch (IOException e) {
			throw new Error("IO exception from Etch", e);
		}
		if (noveltyHandler != null) noveltyHandler.accept(ref);
		return ref.withMinimumStatus(Ref.STORED);
	}
	
	@Override
	public String toString() {
		return "EtchStore at "+etch.getFile();
	}
}
