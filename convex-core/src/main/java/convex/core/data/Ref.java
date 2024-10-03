package convex.core.data;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.function.Consumer;

import convex.core.Constants;
import convex.core.data.prim.CVMBool;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.MissingDataException;
import convex.core.store.AStore;
import convex.core.store.Stores;
import convex.core.util.Trees;

/**
 * Class representing a smart reference to a decentralised data value.
 * 
 * "The greatest trick the Devil ever pulled was convincing the world he didn’t
 * exist." - The Usual Suspects
 * 
 * A Ref itself is not a Cell, but may be contained within a Cell, in which case
 * the Cell class must implement IRefContainer in order to persist and update
 * contained Refs correctly
 * 
 * Refs include a status that indicates the level of validation proven. It is
 * important not to rely on the value of a Ref until it has a sufficient status
 * - e.g. a minimum status of PERSISTED is required to be able to guarantee
 * walking an entire nested data structure.
 * 
 * Guarantees: 
 * - O(1) access to the Hash value, cached on first access 
 * - O(1) access to the referenced object (though may required hitting storage if not cached) 
 * - Indirectly referenced values may be collected by the garbage collector, with the assumption that they can be retrieved from storage if required
 *
 * @param <T> Type of stored value
 */
public abstract class Ref<T extends ACell> extends AObject implements Comparable<Ref<T>>, IWriteable, IValidated {

	/**
	 * Ref status indicating the status of this Ref is unknown. This is the default
	 * for new Refs
	 */
	public static final int UNKNOWN = 0;

	/**
	 * Ref status indicating the Ref has been shallowly persisted in long term
	 * storage. The Ref can be made soft, and retrieved from storage if needed. No
	 * guarantee about the existence / status of any child objects.
	 */
	public static final int STORED = 1;

	/**
	 * Ref status indicating the Ref has been validated. Requires validation of full tree.
	 */
	public static final int VALIDATED = 2;
	
	/**
	 * Ref status indicating the Ref has been deeply persisted in long term storage.
	 * The Ref and its children can be assumed to be accessible for the life of the
	 * storage subsystem execution. Embedded cells can assume persisted at minimum.
	 */
	public static final int PERSISTED = 3;

	/**
	 * Ref status indicating the Ref has been shared by this peer in an announced
	 * Belief. This means that the Peer has a commitment to maintain this data
	 */
	public static final int ANNOUNCED = 4;
	
	/**
	 * Ref status indicating the value is marked in the store for GC copying. Marked values
	 * are retained until next GC cycle
	 */
	public static final int MARKED = 5;
	
	/**
	 * Ref status indicating the Ref in internal data that should never be discarded
	 */
	public static final int INTERNAL = 15;

	

	/**
	 * Maximum Ref status
	 */
	public static final int MAX_STATUS = ANNOUNCED;
	
	/**
	 * Mask for Ref flag bits representing the Status
	 */
	public static final int STATUS_MASK = 0x0F;
	
	/**
	 * Mask bit for a proven embedded value
	 */
	public static final int KNOWN_EMBEDDED_MASK = 0x10;
	
	/**
	 * Mask bit for a proven non-embedded value
	 */
	public static final int NON_EMBEDDED_MASK = 0x20;

	/**
	 * Mask for embedding status
	 */
	public static final int EMBEDDING_MASK = KNOWN_EMBEDDED_MASK | NON_EMBEDDED_MASK;
	
	/**
	 * Mask bit for verified data, especially signatures
	 */
	public static final int VERIFIED_MASK = 0x40;

	/**
	 * Mask bit for bad data, especially signatures proved invalid
	 */
	public static final int BAD_MASK = 0x80;
	
	/**
	 * Mask bit for bad data, especially signatures proved invalid
	 */
	public static final int VERIFICATION_MASK = VERIFIED_MASK | BAD_MASK;

	/**
	 * Flags for embedded values, typically used on creation
	 */
	public static final int VALID_EMBEDDED_FLAGS=STORED|KNOWN_EMBEDDED_MASK|VERIFIED_MASK;
	
	/**
	 * Flags for valid embedded values, typically used on creation
	 */
	public static final int INTERNAL_FLAGS=INTERNAL|VERIFIED_MASK;

	
	/**
	 * Ref status indicating that the Ref refers to data that has been proven to be invalid
	 */
	public static final int INVALID = -1;

	/**
	 * Ref for null value. Important because we can't persist this, since null
	 * collides with the result of an empty soft reference.
	 */
	public static final RefDirect<?> NULL_VALUE = RefDirect.create(null, Hash.NULL_HASH, INTERNAL_FLAGS);

	public static final RefDirect<CVMBool> TRUE_VALUE = RefDirect.create(CVMBool.TRUE, Hash.TRUE_HASH, INTERNAL_FLAGS);
	public static final RefDirect<CVMBool> FALSE_VALUE = RefDirect.create(CVMBool.FALSE, Hash.FALSE_HASH, INTERNAL_FLAGS);

	/**
	 * Length of an external Reference encoding. Will be a tag byte plus the Hash length
	 */
	public static final int INDIRECT_ENCODING_LENGTH = 1+Hash.LENGTH;

	/**
	 * Hash of the serialised representation of the value Computed and stored upon
	 * demand.
	 */
	protected Hash hash;

	/**
	 * Flag values including Status of this Ref. See public Ref status constants.
	 * 
	 * May be incremented atomically in the event of validation, proven storage.
	 */
	protected int flags;

	protected Ref(Hash hash, int flags) {
		this.hash = hash;
		this.flags = flags;
	}

	/**
	 * Gets the status of this Ref
	 * 
	 * @return UNKNOWN, PERSISTED, VERIFIED, ACCOUNCED or INVALID Ref status
	 *         constants
	 */
	public int getStatus() {
		return flags&STATUS_MASK;
	}
	
	/**
	 * Gets flags with an updated status
	 * @param newStatus New status to apply to flags
	 * @return Updated flags (does not change this Ref)
	 */
	public int flagsWithStatus(int newStatus) {
		return (flags&~STATUS_MASK)|(newStatus&STATUS_MASK);
	}
	
	/**
	 * Gets the flags for this Ref
	 * 
	 * @return flag int value
	 */
	public int getFlags() {
		return flags;
	}

	/**
	 * Return a Ref that has the given status, at minimum. If status was updated, returns a new Ref
	 * 
	 * Assumes any necessary changes to storage will be made separately. 
	 * SECURITY: Dangerous if misused since may invalidate storage assumptions
	 * @param newStatus New status to apply to Ref
	 * @return Updated Ref (may be same Ref is status unchanged)
	 */
	public Ref<T> withMinimumStatus(int newStatus) {
		newStatus&=STATUS_MASK;
		int status=getStatus();
		if (status >= newStatus) return this;
		if (status > MAX_STATUS) {
			throw new IllegalArgumentException("Ref status not recognised: " + newStatus);
		}
		int newFlags=(flags&(~STATUS_MASK))|newStatus;
		return withFlags(newFlags);
	}

	/**
	 * Return a a similar Ref of the same type with updated flags. Creates a new Ref if lags have changed.
	 * @param newFlags New flags to set
	 * @return Updated Ref
	 */
	public abstract Ref<T> withFlags(int newFlags);

	/**
	 * Gets the value from this Ref.
	 * 
	 * Important notes: - May throw a MissingDataException if the data does not
	 * exist in available storage - Will return null if and only if the Ref refers
	 * to the null value
	 * 
	 * @return The value contained in this Ref
	 */
	public abstract T getValue();
	
	/**
	 * Get the number of child branches from this Ref
	 * @return Number of branches
	 */
	public int branchCount() {
		ACell v=getValue();
		if (v==null) return 0;
		return v.getBranchCount();
	}

	@Override
	public int hashCode() {
		return getHash().hashCode();
	}
	
	@Override
	public boolean print(BlobBuilder bb, long limit) {
		bb.append("#ref {:hash #hash ");
		bb.append((hash==null)?"nil":hash.toString());
		bb.append(", :flags ");
		bb.append(Integer.toString(flags));
		bb.append("}");
		return bb.check(limit);
	}

	@Override
	public String toString() {
		BlobBuilder sb = new BlobBuilder();
		print(sb,Constants.PRINT_LIMIT);
		return Strings.create(sb.toBlob()).toString();
	}

	@SuppressWarnings("unchecked")
	@Override
	public boolean equals(Object o) {
		if (!(o instanceof Ref)) return false;
		return equals((Ref<T>) o);
	}
	
	/**
	 * Checks if two Ref Values are equal. Equality is defined as referring to the
	 * same data, i.e. have an identical hash.
	 * 
	 * @param a The Ref to compare with
	 * @return true if Refs have the same value, false otherwise
	 */
	public abstract boolean equals(Ref<T> a);

	@Override
	public int compareTo(Ref<T> a) {
		if (this == a) return 0;
		return getHash().compareTo(a.getHash());
	}

	/**
	 * Gets the Hash of this ref's value.
	 * 
	 * @return Hash of the value
	 */
	public abstract Hash getHash();
	
	/**
	 * Gets the Hash of this ref's value, or null if not yet computed
	 * 
	 * @return Hash of the value, or null if not yet computed
	 */
	public final Hash cachedHash() {
		return hash;
	}

	/**
	 * Returns a direct Ref wrapping the given value. Does not perform any Ref
	 * lookup in stores etc.
	 * 
	 * @param value Value to wrap in the Ref
	 * @return New Ref wrapping the given value.
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> Ref<T> get(T value) {
		if (value==null) return (Ref<T>) NULL_VALUE;
		return value.getRef();
	}

	/**
	 * Creates a RefSoft using a specific Hash. Fetches the actual value lazily from the
	 * current thread's store on demand.
	 * 
	 * Internal soft reference may be initially empty: This Ref might not have
	 * available data in the store, in which case calls to getValue() may result in
	 * a MissingDataException
	 * 
	 * WARNING: Does not mark as either embedded or non-embedded, as this might be a top level 
	 * entry in the store. isEmbedded() will query the store to determine status.
	 * 
	 * @param hash The hash value for this Ref to refer to
	 * @return Ref for the specific hash.
	 */
	public static <T extends ACell> RefSoft<T> forHash(Hash hash) {
		return RefSoft.createForHash(hash);
	}
	
	public Ref<T> markEmbedded(boolean isEmbedded) {
		int newFlags=mergeFlags(flags,(isEmbedded?KNOWN_EMBEDDED_MASK:NON_EMBEDDED_MASK));
		flags=newFlags;
		return this;
	}
	
	/**
	 * Sets the Flags for this Ref. WARNING: caller must have performed any necessary validation
	 * @param newFlags Flags to set
	 * @return Updated Ref
	 */
	public Ref<T> setFlags(int newFlags) {
		flags=newFlags;
		return this;
	}
	
	/**
	 * Reads a ref from the given Blob position. Assumes no tag.
	 * 
	 * Marks as non-embedded, since only non-embedded cells should be encoded this way
	 * 
	 * @param b Blob containing the data to read at the current position
	 * @param pos position in Blob to read
	 * @return Ref read from ByteBuffer
	 * @throws BadFormatException If there are insufficient bytes to read a full Ref
	 */
	public static <T extends ACell> Ref<T> readRaw(Blob b, int pos) throws BadFormatException {
		Hash h = Hash.wrap(b,pos);
		if (h==null) throw new BadFormatException("Insufficient bytes to read Ref as position: "+pos);
		Ref<T> ref=Ref.forHash(h);
		ref=ref.markEmbedded(false);
		return ref;
	}

	public void validate() throws InvalidDataException {
		if (hash != null) hash.validate();
		// TODO should be using a stack for validation
		if (getStatus() < VALIDATED) {
			T o = getValue();
			if (o!=null) {
				o.validate();
			}
		}
	}

	/**
	 * Return true if this Ref is a direct reference, i.e. the value is pinned in
	 * memory and cannot be garbage collected
	 * 
	 * @return true if this Ref is direct, false otherwise
	 */
	public abstract boolean isDirect();

	/**
	 * Return true if this Ref's status indicates it has definitely been persisted
	 * to storage.
	 * 
	 * May return false negatives, e.g. the object could be in the store but this
	 * Ref instance still has a status of "UNKNOWN".
	 * 
	 * @return true if this Ref has a status of PERSISTED or above, false otherwise
	 */
	public boolean isPersisted() {
		return getStatus() >= PERSISTED;
	}
	
	/**
	 * Return true if this Ref's status indicates it has definitely been marked within storage
	 * 
	 * May return false negatives, e.g. the object could be marked in the store but this
	 * Ref instance still has a status of "UNKNOWN".
	 * 
	 * @return true if this Ref has a status of MARKED or above, false otherwise
	 */
	public boolean isMarked() {
		return getStatus() >= MARKED;
	}
	
	
	/**
	 * Persists this Ref in the current store if not embedded and not already
	 * persisted.
	 * 
	 * This may convert the Ref from a direct reference to a soft reference.
	 * 
	 * If the persisted Ref represents novelty, will trigger the specified novelty
	 * handler 
	 * 
	 * @param noveltyHandler Novelty handler to call (may be null)
	 * @return the persisted Ref 
	 * @throws IOException in case of IO error during persistence
	 * @throws MissingDataException If the Ref's value does not exist or has been
	 *         garbage collected before being persisted 
	 */
	@SuppressWarnings("unchecked")
	public <R extends ACell> Ref<R> persist(Consumer<Ref<ACell>> noveltyHandler) throws IOException {
		int status = getStatus();
		if (status >= PERSISTED) return (Ref<R>) this; // already persisted in some form
		AStore store=Stores.current();
		return (Ref<R>) store.storeRef(this, Ref.PERSISTED,noveltyHandler);
	}
	
	/**
	 * Persists this Ref in the current store if not embedded and not already
	 * persisted. Resulting status will be PERSISTED or higher.
	 * 
	 * This may convert the Ref from a direct reference to a soft reference.
	 * 
	 * @throws MissingDataException if the Ref cannot be fully persisted.
	 * @return the persisted Ref
	 * @throws IOException in case of IO error during persistence
	 */
	public <R extends ACell> Ref<R> persist() throws IOException {
		return persist(null);
	}

	/**
	 * Updates an array of Refs with the given function.
	 * 
	 * Returns the original array unchanged if no refs were changed, otherwise
	 * returns a new array.
	 * 
	 * @param refs Array of Refs to update
	 * @param func Ref update function
	 * @return Array of updated Refs
	 */
	public static <T extends ACell> Ref<T>[] updateRefs(Ref<T>[] refs, IRefFunction func) {
		Ref<T>[] newRefs = refs;
		int n = refs.length;
		for (int i = 0; i < n; i++) {
			Ref<T> ref = refs[i];
			@SuppressWarnings("unchecked")
			Ref<T> newRef = (Ref<T>) func.apply(ref);
			if (ref != newRef) {
				// Ensure newRefs is a new copy since we are making at least one change
				if (newRefs == refs) {
					newRefs = refs.clone();
				}
				newRefs[i] = newRef;
			}
		}
		return newRefs;
	}

	@SuppressWarnings("unchecked")
	public static <T extends ACell> Ref<T>[] createArray(T[] values) {
		int n = values.length;
		Ref<T>[] refs = new Ref[n];
		for (int i = 0; i < n; i++) {
			refs[i] = Ref.get(values[i]);
		}
		return refs;
	}

	/**
	 * Adds the value of this Ref and all non-embedded child values to a given set.
	 * 
	 * Logically, provides the guarantee that the set will contain all cells needed
	 * to recreate the complete value of this Ref.
	 * 
	 * @param store Store to add to
	 * @return Set containing this Ref and all direct or indirect child refs
	 */
	@SuppressWarnings("unchecked")
	public ASet<ACell> addAllToSet(ASet<ACell> store) {
		store = store.includeRef((Ref<ACell>) this);
		ACell rc = getValue();
		
		int n = rc.getRefCount();
		for (int i = 0; i < n; i++) {
			Ref<ACell> rr = rc.getRef(i);
			if (rr.isEmbedded()) continue;
			store = rr.addAllToSet(store);
		}
		return store;
	}

	/**
	 * Check if the Ref's value is embedded. 
	 * 
	 * If false, the value must be a branch.
	 * 
	 * @return true if embedded, false otherwise
	 */
	public final boolean isEmbedded() {
		if ((flags&KNOWN_EMBEDDED_MASK)!=0) return true; 
		if ((flags&NON_EMBEDDED_MASK)!=0) return false;
		boolean em;
		ACell value=getValue();
		if (value==null) {
			em=true;
		} else {
			em=value.isEmbedded();
		}
		flags=flags|(em?KNOWN_EMBEDDED_MASK:NON_EMBEDDED_MASK);
		return em;
	}

	/**
	 * Converts this Ref to a RefDirect
	 * @return Direct Ref
	 */
	public abstract RefDirect<T> toDirect();
	
	/**
	 * Converts this Ref to a RefSoft. Does not perform any persistence: doing this
	 * may make MissingDataExceptions happen.
	 * @param store store to set
	 * 
	 * @return Direct Ref
	 */
	public abstract RefSoft<T> toSoft(AStore store);

	/**
	 * Persists a Ref shallowly in the current store.
	 * 
	 * Status will be updated to STORED or higher.
	 * 
	 * @return Ref with status of STORED or above
	 * @throws IOException in case of IO error during persistence
	 */
	public <R extends ACell> Ref<R> persistShallow() throws IOException {
		return persistShallow(null);
	}
	
	/**
	 * Persists a Ref shallowly in the current store.
	 * 
	 * Status will be updated STORED or higher. Novelty handler will be called exactly once if and only if
	 * the ref was not previously stored
	 * 
	 * @param noveltyHandler Novelty handler to call (may be null)
	 * @return Ref with status of STORED or above
	 * @throws IOException in case of IO error during persistence
	 */
	@SuppressWarnings("unchecked")
	public <R extends ACell> Ref<R> persistShallow(Consumer<Ref<ACell>> noveltyHandler) throws IOException {
		AStore store=Stores.current();
		return (Ref<R>) store.storeTopRef((Ref<ACell>)this, Ref.STORED, noveltyHandler);
	}

	/**
	 * Updates the value stored within this Ref. New value must be equal in value to the old value 
	 * (identical hash), but may have updated internal refs etc.
	 * 
	 * @param newValue New value
	 * @return Updated Ref. May be identical if value unchanged
	 */
	public abstract Ref<T> withValue(T newValue);
	
	/**
	 * Writes the ref to a byte array. Embeds embedded values as necessary.
	 * @param bs Byte array to encode to
	 * @return Updated position
	 */
	@Override
	public final int encode(byte[] bs, int pos) {
		if (isEmbedded()) {
			T value=getValue();
			return Format.write(bs, pos,value); // handles null and re-uses existing encodings
		} else {
			bs[pos++]=Tag.REF;
			return getHash().getBytes(bs, pos);
		}
	}
	
	@Override
	protected Blob createEncoding() {
		if (isEmbedded()) {
			return Format.encodedBlob(getValue());
		}
		
		byte[] bs=new byte[Ref.INDIRECT_ENCODING_LENGTH];	
		Hash h=getHash();
		int pos=0;
		bs[pos++]=Tag.REF;
		pos=h.getBytes(bs, pos);
		return Blob.wrap(bs,0,pos);
	}

	/**
	 * Gets the encoding length for writing this Ref. Will be equal to the encoding length
	 * of the Ref's value if embedded, otherwise INDIRECT_ENCODING_LENGTH
	 *  
	 * @return Exact length of encoding
	 */
	public final long getEncodingLength() {
		if (isEmbedded()) {
			T value=getValue();
			if (value==null) return 1;
			return value.getEncodingLength();
		} else {
			return Ref.INDIRECT_ENCODING_LENGTH;
		}
	}

	/**
	 * Gets the indirect memory size for this Ref
	 * @return 0 for fully embedded values with no child refs, memory size of referred value otherwise
	 */
	public long getMemorySize() {
		T value=getValue();
		if (value==null) return 0;
		return value.getMemorySize();
	}

	/**
	 * Finds all instances of missing data in this Ref, and adds them to the missing set
	 * @param missingSet Set to add missing instances to
	 * @param limit Maximum number of missing branches to identity
	 */
	public void findMissing(HashSet<Hash> missingSet, long limit) {
		if (getStatus()>=Ref.PERSISTED) return;

		final ArrayList<Ref<?>> stack=new ArrayList<>();
		stack.add(this);
		Consumer<Ref<?>> mf=r->{
			if (missingSet.size()>=limit) return;
			if (missingSet.contains(r.cachedHash())) return;
			if (r.isMissing()) {
				missingSet.add(r.cachedHash());
			} else {
				if (r.getStatus()>=Ref.PERSISTED) return; // proof we have everything below here
					
				// Should be OK to get value, since non-missing!
				ACell val=r.getValue();
				if (val==null) return;
				
				// recursively scan for missing children
				int n=val.getRefCount();
				for (int i=0; i<n; i++) {
					stack.add(val.getRef(i));
				}
			}
		};
		Trees.visitStack(stack, mf);
	}

	/**
	 * Checks if this Ref refers to missing data, i.e. a Cell that does not exist in the
	 * current store. May cause a read to the store.
	 * 
	 * @return true if this specific Ref has missing data, false otherwise.
	 */
	public abstract boolean isMissing();

	/**
	 * Merges flags in an idempotent way. Assume flags are valid. Takes the maximum status
	 * @param a First set of flags
	 * @param b Second set of flags
	 * @return Merged flags
	 */
	public static int mergeFlags(int a, int b) {
		int statusPart=Math.max(a&STATUS_MASK, b& STATUS_MASK);
		int flagsPart=((a|b)&~STATUS_MASK);
		return statusPart|flagsPart;
	}

	/**
	 * Ensures this Ref is canonical
	 * @return this Ref if already canonical, potentially a new Ref with canonical value otherwise
	 */
	public abstract Ref<T> ensureCanonical();

	/**
	 * Updates Refs in an arbitrary Cell
	 * @param <T> Type of Cell
	 * @param o Cell to update
	 * @param func Ref update function
	 * @return Updated Cell (will be the same cell if Refs unchanged)
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> T update(T o, IRefFunction func) {
		if (o==null) return null;
		return (T) o.updateRefs(func);
	}

	@SuppressWarnings("unchecked")
	public static <R extends ACell> R updateRefs(R a, IRefFunction func) {
		if (a==null) return null;
		R b=(R) a.updateRefs(func);
		return b;
	}

	@SuppressWarnings("unchecked")
	public static final <T extends ACell> Ref<T> nil() {
		return (Ref<T>)NULL_VALUE;
	}

	public boolean isInternal() {
		return (flags & STATUS_MASK) == INTERNAL;
	}

	public boolean isValidated() {
		return getStatus()>=VALIDATED;
	}
}
