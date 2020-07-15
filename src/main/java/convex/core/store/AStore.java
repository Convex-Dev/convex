package convex.core.store;

import java.util.function.Consumer;

import convex.core.crypto.Hash;
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
	 * Persists a @Ref in long term storage as defined by this store implementation.
	 * Ensures all nested Refs are also persisted.
	 * 
	 * If the persisted Ref represents novelty (i.e. not previously persisted) Will
	 * call the provided noveltyHandler
	 * 
	 * @param ref A Ref to the given object. Should be either DIRECT or STORED at
	 *            minimum to present risk of MissingDataException.
	 * @return The persisted Ref, of status PERSISTED at mimimum
	 */
	public abstract <T> Ref<T> persistRef(Ref<T> ref, Consumer<Ref<T>> noveltyHandler);

	/**
	 * Stores a @Ref in long term storage as defined by this store implementation.
	 * Does not deference or otherwise do anything with nested Refs.
	 * 
	 * If the persisted Ref represents novelty (i.e. not previously stored) Will
	 * call the provided noveltyHandler
	 * 
	 * @param ref A Ref to the given object. Should be either Direct or STORED at
	 *            minimum to present risk of MissingDataException.
	 * @return The persisted Ref, of status STORED at minimum
	 */
	public abstract <T> Ref<T> storeRef(Ref<T> ref, Consumer<Ref<T>> noveltyHandler);

	/**
	 * Gets the stored Ref for a given hash value, or null if not found.
	 * 
	 * If the result is non-null, the Ref will have a status equal to STORED at minimum.
	 * Calls to Ref.getValue() should therefornever throw MissingDataException.
	 * 
	 * @param hash A hash value to look up in the persisted store
	 * @return The stored Ref, or null if the hash value is not persisted
	 */
	public abstract <T> Ref<T> refForHash(Hash hash);
}
