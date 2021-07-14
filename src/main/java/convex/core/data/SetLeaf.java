package convex.core.data;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.BiFunction;

import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.TODOException;
import convex.core.util.Utils;

/**
 * Limited size Persistent Merkle Set implemented as a small sorted list of
 * Values
 * 
 * Must be sorted by Key hash value to ensure uniqueness of representation
 *
 * @param <T> Type of values
 */
public class SetLeaf<T extends ACell> extends AHashSet<T> {
	/**
	 * Maximum number of entries in a SetLeaf
	 */
	public static final int MAX_ENTRIES = 16;

	private final Ref<T>[] entries;

	SetLeaf(Ref<T>[] items) {
		super(items.length);
		entries = items;
	}

	/**
	 * Creates a SetLeaf with the specified entries. Entries must have distinct keys
	 * but may otherwise be specified in any order.
	 * 
	 * Null entries are ignored/removed.
	 * 
	 * @param entries
	 * @return New ListMap
	 */
	@SafeVarargs
	public static <V extends ACell> SetLeaf<V> create(Ref<V>... entries) {
		return create(entries, 0, entries.length);
	}

	/**
	 * Creates a ListMap with the specified entries. Null entries are
	 * ignored/removed.
	 * 
	 * @param entries
	 * @param offset  Offset into entries array
	 * @param length  Number of entries to take from entries array, starting at
	 *                offset
	 * @return A new ListMap
	 */
	protected static <V extends ACell> SetLeaf<V> create(Ref<V>[] entries, int offset, int length) {
		if (length == 0) return Sets.empty();
		if (length > MAX_ENTRIES) throw new IllegalArgumentException("Too many elements: " + entries.length);
		Ref<V>[] sorted = Utils.copyOfRangeExcludeNulls(entries, offset, offset + length);
		if (sorted.length == 0) return Sets.empty();
		Arrays.sort(sorted);
		return new SetLeaf<V>(sorted);
	}

	@SuppressWarnings("unchecked")
	public static <V extends ACell> SetLeaf<V> create(V item) {
		return new SetLeaf<V>(new Ref[] { Ref.get(item) });
	}

	@Override
	public int size() {
		return entries.length;
	}
	
	@Override
	public Ref<T> getValueRef(ACell k) {
		// Use cached hash if available
		Hash h=(k==null)?Hash.NULL_HASH:k.cachedHash();
		if (h!=null) return getRefByHash(h);

		int len = size();
		for (int i = 0; i < len; i++) {
			Ref<T> e = entries[i];
			if (Utils.equals(k, e.getValue())) return e;
		}
		return null;
	}
	
	@Override
	public boolean containsHash(Hash hash) {
		return getRefByHash(hash) != null;
	}

	@Override
	protected Ref<T> getRefByHash(Hash hash) {
		int len = size();
		for (int i = 0; i < len; i++) {
			Ref<T> e = entries[i];
			if (hash.equals(e.getHash())) return e;
		}
		return null;
	}
	
	/**
	 * Gets the index of key k in the internal array, or -1 if not found
	 * 
	 * @param key
	 * @return
	 */
	private int seek(T key) {
		int len = size();
		for (int i = 0; i < len; i++) {
			if (Utils.equals(key, entries[i].getValue())) return i;
		}
		return -1;
	}
	
	/**
	 * Gets the index of key k in the internal array, or -1 if not found
	 * 
	 * @param key
	 * @return
	 */
	private int seekKeyRef(Ref<T> key) {
		Hash h=key.getHash();
		int len = size();
		for (int i = 0; i < len; i++) {
			if (h.compareTo(entries[i].getHash())==0) return i;
		}
		return -1;
	}

	@SuppressWarnings("unchecked")
	@Override
	public SetLeaf<T> exclude(ACell key) {
		int i = seek((T)key);
		if (i < 0) return this; // not found
		return excludeAt(i);
	}

	@Override
	public SetLeaf<T> excludeRef(Ref<T> key) {
		int i = seekKeyRef(key);
		if (i < 0) return this; // not found
		return excludeAt(i);
	}

	@SuppressWarnings("unchecked")
	private SetLeaf<T> excludeAt(int index) {
		int len = size();
		if (len == 1) return Sets.empty();
		Ref<T>[] newEntries = (Ref<T>[]) new Ref[len - 1];
		System.arraycopy(entries, 0, newEntries, 0, index);
		System.arraycopy(entries, index + 1, newEntries, index, len - index - 1);
		return new SetLeaf<T>(newEntries);
	}

	protected void accumulateValues(ArrayList<T> al) {
		for (int i = 0; i < entries.length; i++) {
			Ref<T> me = entries[i];
			al.add(me.getValue());
		}
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.SET;
		return encodeRaw(bs,pos);
	}
	
	@Override
	public int encodeRaw(byte[] bs, int pos) {
		long n=count();
		pos = Format.writeVLCLong(bs,pos, n);

		for (int i = 0; i < n; i++) {
			pos = entries[i].encode(bs, pos);;
		}
		return pos;
	}

	@Override
	public int estimatedEncodingSize() {
		// allow space for header, size byte, 2 refs per entry
		return 2 + Format.MAX_EMBEDDED_LENGTH * size();
	}
	
	public static int MAX_ENCODING_LENGTH=  2 + MAX_ENTRIES * Format.MAX_EMBEDDED_LENGTH;

	/**
	 * Reads a MapLeaf from the provided ByteBuffer Assumes the header byte is
	 * already read.
	 * 
	 * @param bb ByteBuffer to read from
	 * @param count Count of map elements
	 * @param includeValues True to include values, false otherwise (i.e. this is a Set)
	 * @return A Map as deserialised from the provided ByteBuffer
	 * @throws BadFormatException
	 */
	@SuppressWarnings("unchecked")
	public static <V extends ACell> SetLeaf<V> read(ByteBuffer bb, long count) throws BadFormatException {
		if (count == 0) return Sets.empty();
		if (count < 0) throw new BadFormatException("Negative count of map elements!");
		if (count > MAX_ENTRIES) throw new BadFormatException("MapLeaf too big: " + count);

		Ref<V>[] items = (Ref<V>[]) new Ref[(int) count];
		for (int i = 0; i < count; i++) {
			Ref<V> ref=Format.readRef(bb);
			items[i]=ref;
		}

		if (!isValidOrder(items)) {
			throw new BadFormatException("Bad ordering of keys!");
		}

		return new SetLeaf<V>(items);
	}
	

	@SuppressWarnings("unchecked")
	public static <V extends ACell> SetLeaf<V> emptySet() {
		return (SetLeaf<V>) Sets.EMPTY;
	}

	@Override
	public boolean isCanonical() {
		// validation for both key uniqueness and sort order
		return isValidOrder(entries);
	}
	
	@Override public final boolean isCVMValue() {
		return true;
	}

	private static <V extends ACell> boolean isValidOrder(Ref<V>[] entries) {
		long count = entries.length;
		for (int i = 0; i < count - 1; i++) {
			Hash a = entries[i].getHash();
			Hash b = entries[i + 1].getHash();
			if (a.compareTo(b) >= 0) {
				return false;
			}
		}
		return true;
	}

	@Override
	public int getRefCount() {
		return entries.length;
	}

	@SuppressWarnings("unchecked")
	@Override
	public Ref<T> getRef(int i) {
		Ref<T> e = entries[i]; // IndexOutOfBoundsException if out of range
		return e;
	}
	
	@Override
	public Ref<T> getElementRef(long i) {
		Ref<T> e = entries[Utils.checkedInt(i)]; // Exception if out of range
		return e;
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Override
	public SetLeaf updateRefs(IRefFunction func) {
		int n = entries.length;
		if (n == 0) return this;
		Ref<T>[] newEntries = entries;
		for (int i = 0; i < n; i++) {
			Ref<T> e = newEntries[i];
			Ref<T> newEntry = (Ref<T>) func.apply(e);
			if (e!=newEntry) {
				if (newEntries==entries) newEntries=entries.clone();
				newEntries[i]=newEntry;
			}
		}
		if (newEntries==entries) return this;
		// Note: we assume no key hashes have changed
		return new SetLeaf(newEntries);
	}

	/**
	 * Filters this ListMap to contain only key hashes with the hex digits specified
	 * in the given Mask
	 * 
	 * @param digitPos Position of the hex digit to filter
	 * @param mask     Mask of digits to include
	 * @return Filtered ListMap
	 */
	public SetLeaf<T> filterHexDigits(int digitPos, int mask) {
		mask = mask & 0xFFFF;
		if (mask == 0) return Sets.empty();
		if (mask == 0xFFFF) return this;
		int sel = 0;
		int n = size();
		for (int i = 0; i < n; i++) {
			Hash h = entries[i].getHash();
			if ((mask & (1 << h.getHexDigit(digitPos))) != 0) {
				sel = sel | (1 << i); // include this index in selection
			}
		}
		if (sel == 0) return Sets.empty(); // no entries selected
		return filterEntries(sel);
	}

	/**
	 * Filters entries using the given bit mask
	 * 
	 * @param selection
	 * @return
	 */
	@SuppressWarnings("unchecked")
	private SetLeaf<T> filterEntries(int selection) {
		if (selection == 0) return Sets.empty(); // no items selected
		int n = size();
		if (selection == ((1 << n) - 1)) return this; // all items selected
		Ref<T>[] newEntries = new Ref[Integer.bitCount(selection)];
		int ix = 0;
		for (int i = 0; i < n; i++) {
			if ((selection & (1 << i)) != 0) {
				newEntries[ix++] = entries[i];
			}
		}
		assert (ix == Integer.bitCount(selection));
		return new SetLeaf<T>(newEntries);
	}

	@Override
	public AHashSet<T> mergeWith(AHashSet<T> b, int setOp) {
		return mergeWith(b,setOp,0);
	}
	
	@Override
	protected AHashSet<T>  mergeWith(AHashSet<T>  b, int setOp, int shift) {
		if (b instanceof SetLeaf) return mergeWith((SetLeaf<T>) b, setOp,shift);
		if (b instanceof SetTree) return b.mergeWith(this, reverseOp(setOp),shift);
		throw new TODOException("Unhandled map type: " + b.getClass());
	}

	public AHashSet<T>  mergeWith(SetLeaf<T> b, int setOp,int shift) {
		if (this.equals(b)) return applySelf(setOp); // no change in identical case
		int al = this.size();
		int bl = b.size();
		int ai = 0;
		int bi = 0;
		ArrayList<Ref<T>> results = null;
		while ((ai < al) || (bi < bl)) {
			Ref<T> ae = (ai < al) ? this.entries[ai] : null;
			Ref<T> be = (bi < bl) ? b.entries[bi] : null;
			int c = (ae == null) ? 1 : ((be == null) ? -1 : ae.getHash().compareTo(be.getHash()));
			
			Ref<T> newE;
			if (c==0) {
				newE= applyOp(setOp,ae,be);
			} else if (c<0) {
				// apply to a
				newE= applyOp(setOp,ae,null);
			} else {
				// apply to a
				newE= applyOp(setOp,null,be);
			}
			
			// Create results arraylist if any difference from this
			if ((results == null) && (newE != ((c <= 0) ? ae : null))) {
				// create new results array if difference detected
				results = new ArrayList<>(2*MAX_ENTRIES); // space for all if needed
				// include new entries
				for (int i = 0; i < ai; i++) {
					results.add(entries[i]);
				}
			}
			if (c <= 0) ai++; // inc ai if we used ae
			if (c >= 0) bi++; // inc bi if we used be
			if ((results != null) && (newE != null)) results.add(newE);
		}
		if (results == null) return this;
		return Sets.createWithShift(shift,results);
	}

	public <R> R reduceValues(BiFunction<? super R, ? super T, ? extends R> func, R initial) {
		int n = size();
		R result = initial;
		for (int i = 0; i < n; i++) {
			result = func.apply(result, entries[i].getValue());
		}
		return result;
	}

	@Override
	public boolean equals(ASet<T> a) {
		if (!(a instanceof SetLeaf)) return false;
		return equals((SetLeaf<T>) a);
	}

	public boolean equals(SetLeaf<T> a) {
		if (this == a) return true;
		int n = size();
		if (n != a.size()) return false;
		for (int i = 0; i < n; i++) {
			if (!entries[i].equals(a.entries[i])) return false;
		}
		return true;
	}

	@Override
	protected void validateWithPrefix(String prefix) throws InvalidDataException {
		validate();
		for (int i = 0; i < entries.length; i++) {
			Ref<T> e = entries[i];
			Hash h = e.getHash();
			if (!h.toHexString().startsWith(prefix)) {
				throw new InvalidDataException("Prefix " + prefix + " invalid for set entry: " + e + " with hash: " + h,
						this);
			}
			e.validate();
		}
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if ((count == 0) && (this != Sets.EMPTY)) {
			throw new InvalidDataException("Empty map not using canonical instance", this);
		}

		if (count > MAX_ENTRIES) {
			throw new InvalidDataException("Too many items in list map: " + entries.length, this);
		}

		// validates both key uniqueness and sort order
		if (!isCanonical()) {
			throw new InvalidDataException("Non-canonical key ordering", this);
		}
	}

	@Override
	public boolean containsAll(ASet<T> b) {
		if (this==b) return true;
		
		// if set is too big, can't possibly contain all keys
		if (b.count()>count) return false;
		
		// must be a setleaf if this size or smaller
		return containsAll((SetLeaf<T>)b);
	}
	
	@Override
	public boolean isSubset(ASet<T> b) {
		return b.containsAll(this);
	}
	
	protected boolean containsAll(SetLeaf<T> b) {
		int ix=0;
		for (Ref<T> meb:b.entries) {
			Hash bh=meb.getHash();
			
			if (ix>=count) return false; // no remaining entries in this
			while (ix<count) {
				Ref<T> mea=entries[ix];
				Hash ah=mea.getHash();
				int c=ah.compareTo(bh);
				if (c<0) {
					// ah is smaller than bh
					// need to advance ix and try next entry
					ix++;
					if (ix>=count) return false; // not found
					continue;
				} else if (c>0) {
					return false; // didn't contain the key entry
				} else {
					// found it, so advance to next entry in b and update ix
					ix++;
					break;
				}
			}
		}
		
		return true;
	}

	@Override
	public AHashSet<T> includeRef(Ref<T> ref) {
		return includeRef(ref,0);
	}

	@Override
	protected AHashSet<T> includeRef(Ref<T> e, int shift) {
		int n=entries.length;
		Hash h=e.getHash();
		int pos=0;
		for (; pos<n; pos++) {
			Ref<T> iref=entries[pos];
			int c=h.compareTo(iref.getHash());
			if (c==0) return this;
			if (c<0) break; // need to add at this position
		}
	
		// New element must be added at pos
		@SuppressWarnings("unchecked")
		Ref<T>[] newEntries=new Ref[n+1];
		System.arraycopy(entries, 0, newEntries, 0, pos);
		System.arraycopy(entries, pos, newEntries, pos+1, n-pos);
		newEntries[pos]=e;
		
		if (n<MAX_ENTRIES) {
			// New leaf
			return new SetLeaf<T>(newEntries);
		} else {
			// expand to tree
			return SetTree.create(newEntries, shift);
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	protected <R> void copyToArray(R[] arr, int offset) {
		for (int i=0; i<count; i++) {
			arr[i+offset]=(R)get(i);
		}
	}

	@Override
	public T get(long index) {
		return entries[Utils.checkedInt(index)].getValue();
	}

	@Override
	public AHashSet<T> toCanonical() {
		if (count<=MAX_ENTRIES) return this;
		return SetTree.create(entries, 0);
	}




}
