package convex.core.store;

import java.io.Closeable;
import java.io.IOException;
import java.util.function.Consumer;

import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.MissingDataException;

/**
 * Abstract base class for object storage subsystems
 * 
 * "The perfect kind of architecture decision is the one which never has to be
 * made" ― Robert C. Martin
 *
 */
public abstract class AStore implements Closeable {
	
	/**
	 * Stores a @Ref in long term storage as defined by this store implementation.
	 * 
	 * Will store nested Refs if required.
	 * 
	 * Does not store embedded values. If it is necessary to persist an embedded value
	 * deliberately in the store, use storeTopRef(...) instead.
	 * 
	 * If the persisted Ref represents novelty (i.e. not previously stored) Will
	 * call the provided noveltyHandler.
	 * 
	 * @param ref A Ref to the given object. Should be either Direct or STORED at
	 *            minimum to present risk of MissingDataException.
	 * @param status Status to store at
	 * @param noveltyHandler Novelty Handler function for Novelty detected. May be null.
	 * @return The persisted Ref, of status STORED at minimum
	 * @throws IOException in case of IO error during persistence
	 */
	public abstract <T extends ACell> Ref<T> storeRef(Ref<T> ref, int status,Consumer<Ref<ACell>> noveltyHandler) throws IOException;

	/**
	 * Stores a top level @Ref in long term storage as defined by this store implementation.
	 * 
	 * Will store nested Refs if required. 
	 * 
	 * Will only store an embedded Ref if it is the top level item.
	 * 
	 * If the persisted Ref represents novelty (i.e. not previously stored) Will
	 * call the provided noveltyHandler
	 * 
	 * @param ref A Ref to the given object. Should be either Direct or STORED at
	 *            minimum to present risk of MissingDataException.
	 * @param status Status to store at
	 * @param noveltyHandler Novelty Handler function for Novelty detected. May be null.
	 * @return The persisted Ref, of status STORED at minimum
	 * @throws IOException in case of IO error during persistence
	 */
	public abstract <T extends ACell> Ref<T> storeTopRef(Ref<T> ref, int status,Consumer<Ref<ACell>> noveltyHandler) throws IOException;

	
	/**
	 * Gets the stored Ref for a given hash value, or null if not found in the store.
	 * 
	 * If the result is non-null, the Ref will have a status equal to STORED at minimum.
	 * Calls to Ref.getValue() should therefore never throw MissingDataException.
	 * 
	 * @param hash A hash value to look up in the persisted store
	 * @return The stored Ref, or null if the hash value is not persisted
	 */
	public abstract <T extends ACell> Ref<T> refForHash(Hash hash);

	/**
	 * Gets the hash of the root data from the store. In order to set the root hash, go via setRootData.
	 * 
	 * @return Root hash value from this store.
	 * @throws IOException In case of store IO error
	 */
	public abstract Hash getRootHash() throws IOException;
	
	/**
	 * Gets the Root Data from the Store. Root data is typically used to store the Peer state
	 * in situations where the Peer needs to be restored from persistent storage.
	 * 
	 * @return Root data value from this store. May be nil.
	 * @throws IOException In case of store IO error
	 * @throws MissingDataException if Root Data is missing
	 */
	public <T extends ACell> T getRootData() throws IOException {
		Ref<T> ref=getRootRef();
		if (ref==null) throw new MissingDataException(this,getRootHash());
		return ref.getValue();
	}
	
	/**
	 * Gets a Ref for the Root Data. Root data is typically used to store the Peer state
	 * in situations where the Peer needs to be restored from persistent storage.
	 * 
	 * @return Root Data Ref from this store, or null if not found.
	 * @throws IOException In case of store IO error
	 */
	@SuppressWarnings("unchecked")
	public <T extends ACell> Ref<T> getRootRef() throws IOException {
		Hash h=getRootHash();
		Ref<T> ref=refForHash(h);
		
		// special cases:
		// we always recognise `nil` or the zero hash `0x0000000....` even if not in store
		if ((ref==null) &&((Hash.EMPTY_HASH.equals(h))||(Hash.NULL_HASH.equals(h)))) {
			return (Ref<T>) Ref.NULL_VALUE;
		}
		return ref;
	}

	/**
	 * Sets the root data for this Store
	 * @param data Root data to set
	 * @return Ref to written root data
	 * @throws IOException In case of store IO error
	 */
	public abstract <T extends ACell> Ref<T> setRootData(T data) throws IOException;

	/**
	 * Closes this store and frees associated resources
	 */
	public abstract void close();
	
	/**
	 * Decodes a Cell from an Encoding. Looks up Cell in cache if available. Otherwise
	 * equivalent to Format.read(Blob).
	 * @param encoding Encoding of Cell
	 * @return Decoded Cell (may be a a null value)
	 * 
	 * @throws BadFormatException If cell encoding is invalid
	 */
	public abstract  <T extends ACell> T decode(Blob encoding) throws BadFormatException;

	protected ACell decodeImpl(Blob encoding) throws BadFormatException {
		ACell decoded=Format.read(encoding);
		return decoded;
	}

	/**
	 * checks in-memory cache for a stored Ref. Returns store-native Ref if found, null otherwise. Does not access underlying storage.
	 * @param <T> Type of Cell
	 * @param h Hash to check
	 * @return Stored Ref, or null if not found (may still be in persistent store)
	 */
	public abstract <T extends ACell> Ref<T> checkCache(Hash h);

	public abstract String shortName();
}
