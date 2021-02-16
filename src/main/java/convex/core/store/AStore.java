package convex.core.store;

import java.io.IOException;
import java.util.function.Consumer;

import convex.core.crypto.Hash;
import convex.core.data.ACell;
import convex.core.data.Ref;

/**
 * Abstract base class for object storage subsystems
 * 
 * "The perfect kind of architecture decision is the one which never has to be
 * made" ― Robert C. Martin
 *
 */
public abstract class AStore {
	
	/**
	 * Stores a @Ref in long term storage as defined by this store implementation.
	 * 
	 * Will store nested Refs if required.
	 * 
	 * Does not store embedded values. If it is necessary to persist an embedded value
	 * deliberately in the sture, use storeTopRef(...) instead.
	 * 
	 * If the persisted Ref represents novelty (i.e. not previously stored) Will
	 * call the provided noveltyHandler.
	 * 
	 * @param ref A Ref to the given object. Should be either Direct or STORED at
	 *            minimum to present risk of MissingDataException.
	 * @return The persisted Ref, of status STORED at minimum
	 */
	public abstract <T extends ACell> Ref<T> storeRef(Ref<T> ref, int status,Consumer<Ref<ACell>> noveltyHandler);

	/**
	 * Stores a top level @Ref in long term storage as defined by this store implementation.
	 * 
	 * Will store nested Refs if required.
	 * 
	 * If the persisted Ref represents novelty (i.e. not previously stored) Will
	 * call the provided noveltyHandler
	 * 
	 * @param ref A Ref to the given object. Should be either Direct or STORED at
	 *            minimum to present risk of MissingDataException.
	 * @return The persisted Ref, of status STORED at minimum
	 */
	public abstract <T extends ACell> Ref<T> storeTopRef(Ref<T> ref, int status,Consumer<Ref<ACell>> noveltyHandler);

	
	/**
	 * Gets the stored Ref for a given hash value, or null if not found.
	 * 
	 * If the result is non-null, the Ref will have a status equal to STORED at minimum.
	 * Calls to Ref.getValue() should therefore never throw MissingDataException.
	 * 
	 * @param hash A hash value to look up in the persisted store
	 * @return The stored Ref, or null if the hash value is not persisted
	 */
	public abstract <T extends ACell> Ref<T> refForHash(Hash hash);

	/**
	 * Gets the Root Hash from the Store. Root hash is typically used to store the Peer state
	 * in situations where the Peer needs to be restored from persistent storage.
	 * 
	 * @return Root hash value from this store.
	 * @throws IOException
	 */
	public abstract Hash getRootHash() throws IOException;

	/**
	 * Sets the root hash for this Store
	 * @param h
	 * @return
	 * @throws IOException 
	 */
	public abstract void setRootHash(Hash h) throws IOException;

	/**
	 * Closes this store and frees associated resources
	 */
	public abstract void close();
}
