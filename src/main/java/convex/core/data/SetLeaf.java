package convex.core.data;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.TODOException;
import convex.core.util.MergeFunction;
import convex.core.util.Utils;

/**
 * Limited size Persistent Merkle Set implemented as a small sorted list of
 * Values
 * 
 * Must be sorted by Key hash value to ensure uniqueness of representation
 *
 * @param <V> Type of values
 */
public class SetLeaf<V extends ACell> extends AHashSet<V> {
	/**
	 * Maximum number of entries in a SetLeaf
	 */
	public static final int MAX_ENTRIES = 8;

	static final Ref<?>[] EMPTY_ENTRIES = new Ref[0];

	@SuppressWarnings({ "unchecked", "rawtypes" })
	static final SetLeaf<?> EMPTY = new SetLeaf(EMPTY_ENTRIES);

	private final Ref<V>[] entries;

	private SetLeaf(Ref<V>[] items) {
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
	public static <V extends ACell> SetLeaf<V> create(Ref<V>[] entries) {
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
		if (length == 0) return empty();
		if (length > MAX_ENTRIES) throw new IllegalArgumentException("Too many elements: " + entries.length);
		Ref<V>[] sorted = Utils.copyOfRangeExcludeNulls(entries, offset, offset + length);
		if (sorted.length == 0) return empty();
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

	@SuppressWarnings("unchecked")
	@Override
	public boolean containsKey(ACell key) {
		return getValueRef(key) != null;
	}

	@Override
	public Ref<V> getValueRef(ACell k) {
		Hash h=(k==null)?Hash.NULL_HASH:k.cachedHash();
		if (h!=null) return getRefByHash(h);

		int len = size();
		for (int i = 0; i < len; i++) {
			Ref<V> e = entries[i];
			if (Utils.equals(k, e.getValue())) return e;
		}
		return null;
	}

	@Override
	protected Ref<V> getRefByHash(Hash hash) {
		int len = size();
		for (int i = 0; i < len; i++) {
			Ref<V> e = entries[i];
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
	private int seek(V key) {
		int len = size();
		for (int i = 0; i < len; i++) {
			if (Utils.equals(key, entries[i].getValue())) return i;
		}
		return -1;
	}

	@SuppressWarnings("unchecked")
	@Override
	public SetLeaf<V> dissoc(ACell key) {
		int i = seek((V)key);
		if (i < 0) return this; // not found
		return dissocEntry(i);
	}

	@Override
	public SetLeaf<V> dissocRef(Ref<V> key) {
		int i = seekKeyRef(key);
		if (i < 0) return this; // not found
		return dissocEntry(i);
	}

	@SuppressWarnings("unchecked")
	private SetLeaf<V> dissocEntry(int internalIndex) {
		int len = size();
		if (len == 1) return empty();
		Ref<V>[] newEntries = (Ref<V>[]) new Ref[len - 1];
		System.arraycopy(entries, 0, newEntries, 0, internalIndex);
		System.arraycopy(entries, internalIndex + 1, newEntries, internalIndex, len - internalIndex - 1);
		return new SetLeaf<V>(newEntries);
	}

	@Override
	protected void accumulateValues(ArrayList<V> al) {
		for (int i = 0; i < entries.length; i++) {
			Ref<V> me = entries[i];
			al.add(me.getValue());
		}
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.SET;
		return encodeRaw(bs,pos);
	}
	
	@Override
	public int write(byte[] bs, int pos) {
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
		return (SetLeaf<V>) EMPTY;
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
		return 2 * entries.length;
	}

	@SuppressWarnings("unchecked")
	@Override
	public Ref<V> getRef(int i) {
		Ref<V> e = entries[i]; // IndexOutOfBoundsException if out of range
		return e;
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Override
	public SetLeaf updateRefs(IRefFunction func) {
		int n = entries.length;
		if (n == 0) return this;
		Ref<V>[] newEntries = entries;
		for (int i = 0; i < n; i++) {
			Ref<V> e = newEntries[i];
			Ref<V> newEntry = (Ref<V>) func.apply(e);
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
	public SetLeaf<V> filterHexDigits(int digitPos, int mask) {
		mask = mask & 0xFFFF;
		if (mask == 0) return empty();
		if (mask == 0xFFFF) return this;
		int sel = 0;
		int n = size();
		for (int i = 0; i < n; i++) {
			Hash h = entries[i].getHash();
			if ((mask & (1 << h.getHexDigit(digitPos))) != 0) {
				sel = sel | (1 << i); // include this index in selection
			}
		}
		if (sel == 0) return empty(); // no entries selected
		return filterEntries(sel);
	}

	/**
	 * Filters entries using the given bit mask
	 * 
	 * @param selection
	 * @return
	 */
	@SuppressWarnings("unchecked")
	private SetLeaf<V> filterEntries(int selection) {
		if (selection == 0) return empty(); // no items selected
		int n = size();
		if (selection == ((1 << n) - 1)) return this; // all items selected
		Ref<V>[] newEntries = new Ref[Integer.bitCount(selection)];
		int ix = 0;
		for (int i = 0; i < n; i++) {
			if ((selection & (1 << i)) != 0) {
				newEntries[ix++] = entries[i];
			}
		}
		assert (ix == Integer.bitCount(selection));
		return new SetLeaf<V>(newEntries);
	}

	@Override
	public ASet<V> mergeWith(ASet<V> b, MergeFunction<V> func) {
		return mergeWith(b, func, 0);
	}

	@Override
	protected ASet<V> mergeWith(ASet<V> b, MergeFunction<V> func, int shift) {
		if (b instanceof SetLeaf) return mergeWith((SetLeaf<K, V>) b, func, shift);
		if (b instanceof SetTree) return ((SetTree<V>) b).mergeWith(this, func.reverse());
		throw new TODOException("Unhandled set type: " + b.getClass());
	}

	@SuppressWarnings("null")
	private ASet<V> mergeWith(SetLeaf<V> b, MergeFunction<V> func, int shift) {
		int al = this.size();
		int bl = b.size();
		int ai = 0;
		int bi = 0;
		// Complexity to manage:
		// 1. Must step through two ListMaps in order, comparing for key hashes
		// 2. nulls can be produced to remove entries
		// 3. We use the creation of a results ArrayList to signal a change from
		// original value
		ArrayList<Ref<V>> results = null;
		while ((ai < al) || (bi < bl)) {
			Ref<V> ae = (ai < al) ? this.entries[ai] : null;
			Ref<V> be = (bi < bl) ? b.entries[bi] : null;
			
			// comparison
			int c = (ae == null) ? 1 : ((be == null) ? -1 : ae.getHash().compareTo(be.getHash()));
			
			// new entry
			Ref<V> newE = null;
			if (c < 0) {
				V r = func.merge(ae.getValue(), null);
				if (r != null) newE = ae.withValue(r);
			} else if (c > 0) {
				V r = func.merge(null, be.getValue());
				if (r != null) newE = be.withValue(r);
			} else {
				// we have matched keys
				V r = func.merge(ae.getValue(), be.getValue());
				if (r != null) newE = ae.withValue(r);
			}
			if ((results == null) && (newE != ((c <= 0) ? ae : null))) {
				// create new results array if difference detected
				results = new ArrayList<>(16);
				for (int i = 0; i < ai; i++) { // copy previous values in this map, up to ai
					results.add(entries[i]);
				}
			}
			if (c <= 0) ai++; // inc ai if we used ae
			if (c >= 0) bi++; // inc bi if we used be
			if ((results != null) && (newE != null)) results.add(newE);
		}
		if (results == null) return this; // no change detected
		return Sets.createWithShift(shift, results);
	}

	@Override
	public AHashSet<V> mergeDifferences(AHashSet<V> b, MergeFunction<V> func) {
		return mergeDifferences(b,func,0);
	}
	
	@Override
	@Override
	protected AHashSet<V>  mergeDifferences(AHashSet<V>  b, MergeFunction<V> func, int shift) {
		if (b instanceof SetLeaf) return mergeDifferences((SetLeaf<V>) b, func,shift);
		if (b instanceof SetTree) return b.mergeWith(this, func.reverse());
		throw new TODOException("Unhandled map type: " + b.getClass());
	}

	@SuppressWarnings("null")
	public AHashSet<V>  mergeDifferences(SetLeaf<V> b, MergeFunction<V> func,int shift) {
		if (this.equals(b)) return this; // no change in identical case
		int al = this.size();
		int bl = b.size();
		int ai = 0;
		int bi = 0;
		ArrayList<Ref<V>> results = null;
		while ((ai < al) || (bi < bl)) {
			Ref<V> ae = (ai < al) ? this.entries[ai] : null;
			Ref<V> be = (bi < bl) ? b.entries[bi] : null;
			int c = (ae == null) ? 1 : ((be == null) ? -1 : ae.getHash().compareTo(be.getHash()));
			Ref<V> newE = null;
			if (c < 0) {
				// lowest key in this map only
				V r = func.merge(ae.getValue(), null);
				if (r != null) newE = ae.withValue(r);
			} else if (c > 0) {
				// lowest key in other map b only
				V r = func.merge(null, be.getValue());
				if (r != null) newE = be.withValue(r);
			} else {
				// keys are equal (i.e. value in both maps)
				V av = ae.getValue();
				V bv = be.getValue();
				V r = (Utils.equals(av, bv)) ? av : func.merge(ae.getValue(), be.getValue());
				if (r != null) newE = ae.withValue(r);
			}
			if ((results == null) && (newE != ((c <= 0) ? ae : null))) {
				// create new results array if difference detected
				results = new ArrayList<>(16);
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

	public <R> R reduceValues(BiFunction<? super R, ? super V, ? extends R> func, R initial) {
		int n = size();
		R result = initial;
		for (int i = 0; i < n; i++) {
			result = func.apply(result, entries[i].getValue());
		}
		return result;
	}

	@Override
	public boolean equals(ASet<V> a) {
		if (!(a instanceof SetLeaf)) return false;
		return equals((SetLeaf<V>) a);
	}

	public boolean equals(SetLeaf<V> a) {
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
			Ref<V> e = entries[i];
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
		if ((count == 0) && (this != EMPTY)) {
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
	public boolean containsAll(ASet<V> b) {
		if (this==b) return true;
		
		// if set is too big, can't possibly contain all keys
		if (b.count()>count) return false;
		
		// must be a setleaf if this size or smaller
		return containsAll((SetLeaf<V>)b);
	}
	
	protected boolean containsAll(SetLeaf<V> b) {
		int ix=0;
		for (Ref<V> meb:b.entries) {
			Hash bh=meb.getHash();
			
			if (ix>=count) return false; // no remaining entries in this
			while (ix<count) {
				Ref<V> mea=entries[ix];
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


}
