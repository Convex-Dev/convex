package convex.core.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.Panic;
import convex.core.util.MergeFunction;
import convex.core.util.Utils;

/**
 * Limited size Persistent Merkle Map implemented as a small sorted list of
 * Key/Value pairs
 * 
 * Must be sorted by Key hash value to ensure uniqueness of representation
 *
 * @param <K> Type of keys
 * @param <V> Type of values
 */
public class MapLeaf<K extends ACell, V extends ACell> extends AHashMap<K, V> {
	/**
	 * Maximum number of entries in a MapLeaf
	 */
	public static final int MAX_ENTRIES = 8;

	static final MapEntry<?, ?>[] EMPTY_ENTRIES = new MapEntry[0];

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static final MapLeaf<?, ?> EMPTY = new MapLeaf(EMPTY_ENTRIES);

	private final MapEntry<K, V>[] entries;

	private MapLeaf(MapEntry<K, V>[] items) {
		super(items.length);
		entries = items;
	}

	/**
	 * Creates a ListMap with the specified entries. Entries must have distinct keys
	 * but may otherwise be specified in any order.
	 * 
	 * Null entries are ignored/removed.
	 * 
	 * @param entries Entries for map
	 * @return New ListMap
	 */
	public static <K extends ACell, V extends ACell> MapLeaf<K, V> create(MapEntry<K, V>[] entries) {
		return create(entries, 0, entries.length);
	}

	/**
	 * Creates a MapLeaf with the specified entries. Null entries are
	 * ignored/removed.
	 * 
	 * @param entries
	 * @param offset  Offset into entries array
	 * @param length  Number of entries to take from entries array, starting at
	 *                offset
	 * @return A new ListMap
	 */
	protected static <K extends ACell, V extends ACell> MapLeaf<K, V> create(MapEntry<K, V>[] entries, int offset, int length) {
		if (length == 0) return emptyMap();
		if (length > MAX_ENTRIES) throw new IllegalArgumentException("Too many entries: " + entries.length);
		MapEntry<K, V>[] sorted = Utils.copyOfRangeExcludeNulls(entries, offset, offset + length);
		if (sorted.length == 0) return emptyMap();
		Arrays.sort(sorted);
		return new MapLeaf<K, V>(sorted);
	}

	@SuppressWarnings("unchecked")
	public static <K extends ACell, V extends ACell> MapLeaf<K, V> create(MapEntry<K, V> item) {
		return new MapLeaf<K, V>((MapEntry<K, V>[]) new MapEntry<?, ?>[] { item });
	}
	
	/**
	 * Creates a {@link MapLeaf} 
	 * @param <K> Type of keys
	 * @param <V> Type of values
	 * @param items Array of map entries
	 * @return Potentially invalid MapLeaf
	 */
	@SuppressWarnings("unchecked")
	public static <K extends ACell, V extends ACell> MapLeaf<K, V> unsafeCreate(MapEntry<K, V>... items) {
		return new MapLeaf<K,V>(items);
	}

	@Override
	public MapEntry<K, V> getEntry(ACell k) {
		int len = size();
		for (int i = 0; i < len; i++) {
			MapEntry<K, V> e = entries[i];
			if (Cells.equals(k, e.getKey())) return e;
		}
		return null;
	}

	@Override
	public MapEntry<K, V> getKeyRefEntry(Ref<ACell> ref) {
		int len = size();
		for (int i = 0; i < len; i++) {
			MapEntry<K, V> e = entries[i];
			if (ref.equals(e.getKeyRef())) return e;
		}
		return null;
	}

	@Override
	protected MapEntry<K, V> getEntryByHash(Hash hash) {
		int len = size();
		for (int i = 0; i < len; i++) {
			MapEntry<K, V> e = entries[i];
			if (hash.equals(e.getKeyHash())) return e;
		}
		return null;
	}

	@Override
	public boolean containsValue(ACell value) {
		int len = size();
		for (int i = 0; i < len; i++) {
			if (Cells.equals(value, entries[i].getValue())) return true;
		}
		return false;
	}

	@SuppressWarnings("unchecked")
	@Override
	public V get(ACell key) {
		MapEntry<K, V> me = getEntry((K) key);
		return (me == null) ? null : me.getValue();
	}

	/**
	 * Gets the index of key k in the internal array, or -1 if not found
	 * 
	 * @param key
	 * @return
	 */
	private int seek(K key) {
		int len = size();
		for (int i = 0; i < len; i++) {
			if (Cells.equals(key, entries[i].getKey())) return i;
		}
		return -1;
	}

	private int seekKeyRef(Ref<K> key) {
		int len = size();
		for (int i = 0; i < len; i++) {
			if (Utils.equals(key, entries[i].getKeyRef())) return i;
		}
		return -1;
	}

	@SuppressWarnings("unchecked")
	@Override
	public MapLeaf<K, V> dissoc(ACell key) {
		int i = seek((K)key);
		if (i < 0) return this; // not found
		return dissocEntry(i);
	}

	@Override
	public MapLeaf<K, V> dissocRef(Ref<K> key) {
		int i = seekKeyRef(key);
		if (i < 0) return this; // not found
		return dissocEntry(i);
	}

	@SuppressWarnings("unchecked")
	private MapLeaf<K, V> dissocEntry(int internalIndex) {
		int len = size();
		if (len == 1) return emptyMap();
		MapEntry<K, V>[] newEntries = (MapEntry<K, V>[]) new MapEntry[len - 1];
		System.arraycopy(entries, 0, newEntries, 0, internalIndex);
		System.arraycopy(entries, internalIndex + 1, newEntries, internalIndex, len - internalIndex - 1);
		return new MapLeaf<K, V>(newEntries);
	}

	@Override
	public AHashMap<K, V> assocEntry(MapEntry<K, V> e) {
		return assocEntry(e, 0);
	}

	@Override
	public AHashMap<K, V> assocEntry(MapEntry<K, V> e, int shift) {
		int len = size();

		// first check for update with existing key
		for (int i = 0; i < len; i++) {
			MapEntry<K, V> me = entries[i];
			if (e.equals(me)) return this;
			if (me.getKeyRef().equals(e.getKeyRef())) {
				// replace current entry
				MapEntry<K, V>[] newEntries = entries.clone();
				newEntries[i] = e;
				return new MapLeaf<K, V>(newEntries);
			}
		}

		// need to extend array, use new shift if promoting to TreeMap
		int newLen = len + 1;
		@SuppressWarnings("unchecked")
		MapEntry<K, V>[] newEntries = (MapEntry<K, V>[]) new MapEntry<?, ?>[newLen];
		System.arraycopy(entries, 0, newEntries, 0, len);
		newEntries[newLen - 1] = e;
		if (newLen <= MAX_ENTRIES) {
			// new size implies a ListMap
			Arrays.sort(newEntries);
			return new MapLeaf<K, V>(newEntries);
		} else {
			// new size implies a TreeMap with the current given shift
			return MapTree.create(newEntries, shift);
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public AHashMap<K, V> assoc(ACell key, ACell value) {
		return assoc((K)key, (V) value, 0);
	}

	protected AHashMap<K, V> assoc(K key, V value, int shift) {
		int len = size();

		// first check for update with existing key
		for (int i = 0; i < len; i++) {
			MapEntry<K, V> me = entries[i];
			if (Cells.equals(me.getKey(), key)) {
				if (Cells.equals(me.getValue(), value)) return this;
				MapEntry<K, V> newEntry = me.withValue(value);
				if (me == newEntry) return this;

				// need to clone and update array
				MapEntry<K, V>[] newEntries = entries.clone();
				newEntries[i] = newEntry;
				return new MapLeaf<K, V>(newEntries);
			}
		}

		// Key not found, so need to extend array
		@SuppressWarnings("unchecked")
		MapEntry<K, V>[] newEntries = (MapEntry<K, V>[]) new MapEntry<?, ?>[len + 1];
		System.arraycopy(entries, 0, newEntries, 0, len);
		newEntries[len] = MapEntry.create(key, value);
		if (len + 1 <= MAX_ENTRIES) {
			// new size should be a ListMap
			Arrays.sort(newEntries);
			return new MapLeaf<K, V>(newEntries);
		} else {
			// new Size should be a TreeMap with current shift
			return MapTree.create(newEntries, shift);
		}
	}

	@Override
	protected AHashMap<K, V> assocRef(Ref<K> keyRef, V value, int shift) {
		return assoc(keyRef.getValue(), value, shift);
	}

	@Override
	public AHashMap<K, V> assocRef(Ref<K> keyRef, V value) {
		return assocRef(keyRef, value, 0);
	}

	@Override
	public Set<K> keySet() {
		int len = size();
		HashSet<K> h = new HashSet<K>(len);
		;
		for (int i = 0; i < len; i++) {
			MapEntry<K, V> me = entries[i];
			h.add(me.getKey());
		}
		return h;
	}

	@Override
	protected void accumulateKeySet(Set<K> h) {
		for (int i = 0; i < entries.length; i++) {
			MapEntry<K, V> me = entries[i];
			h.add(me.getKey());
		}
	}

	@Override
	protected void accumulateValues(java.util.List<V> al) {
		for (int i = 0; i < entries.length; i++) {
			MapEntry<K, V> me = entries[i];
			al.add(me.getValue());
		}
	}

	@Override
	protected void accumulateEntrySet(Set<Entry<K, V>> h) {
		for (int i = 0; i < entries.length; i++) {
			MapEntry<K, V> me = entries[i];
			h.add(me);
		}
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.MAP;
		return encodeRaw(bs,pos);
	}
	
	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos = Format.writeVLCLong(bs,pos, count);

		for (int i = 0; i < count; i++) {
			// Note we encode the Map Entry refs only, skipping the general vector encoding
			pos = entries[i].encodeRefs(bs,pos);
		}
		return pos;
	}

	@Override
	public int estimatedEncodingSize() {
		// allow space for header, size byte, 2 refs per entry
		return 2 + 2* Format.MAX_EMBEDDED_LENGTH * size();
	}
	
	public static int MAX_ENCODING_LENGTH=  2 + 2 * MAX_ENTRIES * Format.MAX_EMBEDDED_LENGTH;

	/**
	 * Reads a MapLeaf from the provided Blob encoding.
	 * 
	 * @param b Blob to read from
	 * @param pos Start position in Blob (index of tag byte)
	 * @param count Count of map elements
	 * @return A Map as deserialised from the provided ByteBuffer
	 * @throws BadFormatException If encoding is invalid
	 */
	public static <K extends ACell, V extends ACell> MapLeaf<K, V> read(Blob b, int pos, long count) throws BadFormatException {
		int epos=pos+2; // Note: Tag byte plus VLC length of count which is always 1
		
		@SuppressWarnings("unchecked")
		MapEntry<K, V>[] items = (MapEntry<K, V>[]) new MapEntry[(int) count];
		for (int i = 0; i < count; i++) {
			Ref<K> kr=Format.readRef(b,epos);
			epos+=kr.getEncodingLength();
			Ref<V> vr=Format.readRef(b,epos);
			epos+=vr.getEncodingLength();
			items[i] = MapEntry.createRef(kr, vr);
		}

		if (!isValidOrder(items)) {
			throw new BadFormatException("Bad ordering of keys!");
		}
		
		MapLeaf<K,V> result=new MapLeaf<>(items);
		result.attachEncoding(b.slice(pos, epos));
		return result;
	}
	

	@SuppressWarnings("unchecked")
	public static <K extends ACell, V extends ACell> MapLeaf<K, V> emptyMap() {
		return (MapLeaf<K, V>) EMPTY;
	}

	@Override
	public void forEach(BiConsumer<? super K, ? super V> action) {
		for (MapEntry<K, V> e : entries) {
			action.accept(e.getKey(), e.getValue());
		}
	}

	@Override
	public boolean isCanonical() {
		return true;
	}
	
	@Override public final boolean isCVMValue() {
		return true;
	}

	private static <K extends ACell, V extends ACell> boolean isValidOrder(MapEntry<K, V>[] entries) {
		long count = entries.length;
		for (int i = 0; i < count - 1; i++) {
			Hash a = entries[i].getKeyHash();
			Hash b = entries[i + 1].getKeyHash();
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
	public <R extends ACell> Ref<R> getRef(int i) {
		MapEntry<K, V> e = entries[i >> 1]; // IndexOutOfBoundsException if out of range
		if ((i & 1) == 0) {
			return (Ref<R>) e.getKeyRef();
		} else {
			return (Ref<R>) e.getValueRef();
		}
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Override
	public MapLeaf updateRefs(IRefFunction func) {
		int n = entries.length;
		if (n == 0) return this;
		MapEntry<K, V>[] newEntries = entries;
		for (int i = 0; i < n; i++) {
			MapEntry<K, V> e = newEntries[i];
			MapEntry<K, V> newEntry = e.updateRefs(func);
			if (e!=newEntry) {
				if (newEntries==entries) newEntries=entries.clone();
				newEntries[i]=newEntry;
			}
		}
		if (newEntries==entries) return this;
		// Note: we assume no key hashes have changed
		MapLeaf result= new MapLeaf(newEntries);
		result.attachEncoding(encoding); // this is an optimisation to avoid re-encoding
		return result;
	}

	/**
	 * Filters this ListMap to contain only key hashes with the hex digits specified
	 * in the given Mask
	 * 
	 * @param digitPos Position of the hex digit to filter
	 * @param mask     Mask of digits to include
	 * @return Filtered ListMap
	 */
	public MapLeaf<K, V> filterHexDigits(int digitPos, int mask) {
		mask = mask & 0xFFFF;
		if (mask == 0) return emptyMap();
		if (mask == 0xFFFF) return this;
		int sel = 0;
		int n = size();
		for (int i = 0; i < n; i++) {
			Hash h = entries[i].getKeyHash();
			if ((mask & (1 << h.getHexDigit(digitPos))) != 0) {
				sel = sel | (1 << i); // include this index in selection
			}
		}
		if (sel == 0) return emptyMap(); // no entries selected
		return filterEntries(sel);
	}

	/**
	 * Filters entries using the given bit mask
	 * 
	 * @param selection
	 * @return
	 */
	@SuppressWarnings("unchecked")
	private MapLeaf<K, V> filterEntries(int selection) {
		if (selection == 0) return emptyMap(); // no items selected
		int n = size();
		if (selection == ((1 << n) - 1)) return this; // all items selected
		MapEntry<K, V>[] newEntries = new MapEntry[Integer.bitCount(selection)];
		int ix = 0;
		for (int i = 0; i < n; i++) {
			if ((selection & (1 << i)) != 0) {
				newEntries[ix++] = entries[i];
			}
		}
		assert (ix == Integer.bitCount(selection));
		return new MapLeaf<K, V>(newEntries);
	}

	@Override
	public MapEntry<K, V> entryAt(long i) {
		return entries[(int)i];
	}

	@Override
	public AHashMap<K, V> mergeWith(AHashMap<K, V> b, MergeFunction<V> func) {
		return mergeWith(b, func, 0);
	}

	@Override
	protected AHashMap<K, V> mergeWith(AHashMap<K, V> b, MergeFunction<V> func, int shift) {
		if (b instanceof MapLeaf) return mergeWith((MapLeaf<K, V>) b, func, shift);
		if (b instanceof MapTree) return ((MapTree<K, V>) b).mergeWith(this, func.reverse());
		throw new Panic("Unhandled map type: " + b.getClass());
	}

	@SuppressWarnings("null")
	private AHashMap<K, V> mergeWith(MapLeaf<K, V> b, MergeFunction<V> func, int shift) {
		int al = this.size();
		int bl = b.size();
		int ai = 0;
		int bi = 0;
		// Complexity to manage:
		// 1. Must step through two ListMaps in order, comparing for key hashes
		// 2. nulls can be produced to remove entries
		// 3. We use the creation of a results ArrayList to signal a change from
		// original value
		ArrayList<MapEntry<K, V>> results = null;
		while ((ai < al) || (bi < bl)) {
			MapEntry<K, V> ae = (ai < al) ? this.entries[ai] : null;
			MapEntry<K, V> be = (bi < bl) ? b.entries[bi] : null;
			
			// comparison
			int c = (ae == null) ? 1 : ((be == null) ? -1 : ae.getKeyHash().compareTo(be.getKeyHash()));
			
			// new entry
			MapEntry<K, V> newE = null;
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
		return Maps.createWithShift(shift, results);
	}

	@Override
	public AHashMap<K, V> mergeDifferences(AHashMap<K, V> b, MergeFunction<V> func) {
		return mergeDifferences(b,func,0);
	}
	
	@Override
	protected AHashMap<K, V> mergeDifferences(AHashMap<K, V> b, MergeFunction<V> func, int shift) {
		if (b instanceof MapLeaf) return mergeDifferences((MapLeaf<K, V>) b, func,shift);
		if (b instanceof MapTree) return b.mergeWith(this, func.reverse());
		throw new Panic("Unhandled map type: " + b.getClass());
	}

	@SuppressWarnings("null")
	public AHashMap<K, V> mergeDifferences(MapLeaf<K, V> b, MergeFunction<V> func,int shift) {
		if (this.equals(b)) return this; // no change in identical case
		int al = this.size();
		int bl = b.size();
		int ai = 0;
		int bi = 0;
		ArrayList<MapEntry<K, V>> results = null;
		while ((ai < al) || (bi < bl)) {
			MapEntry<K, V> ae = (ai < al) ? this.entries[ai] : null;
			MapEntry<K, V> be = (bi < bl) ? b.entries[bi] : null;
			int c = (ae == null) ? 1 : ((be == null) ? -1 : ae.getKeyHash().compareTo(be.getKeyHash()));
			MapEntry<K, V> newE = null;
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
				V r = (Cells.equals(av, bv)) ? av : func.merge(ae.getValue(), be.getValue());
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
		return Maps.createWithShift(shift,results);
	}

	@Override
	public <R> R reduceValues(BiFunction<? super R, ? super V, ? extends R> func, R initial) {
		int n = size();
		R result = initial;
		for (int i = 0; i < n; i++) {
			result = func.apply(result, entries[i].getValue());
		}
		return result;
	}

	@Override
	public <R> R reduceEntries(BiFunction<? super R, MapEntry<K, V>, ? extends R> func, R initial) {
		int n = size();
		R result = initial;
		for (int i = 0; i < n; i++) {
			result = func.apply(result, entries[i]);
		}
		return result;
	}

	@SuppressWarnings("unchecked")
	@Override
	public boolean equals(ACell a) {
		if (!(a instanceof MapLeaf)) return false;
		return equals((MapLeaf<K, V>) a);
	}

	public boolean equals(MapLeaf<K, V> a) {
		if (this == a) return true;
		int n = size();
		if (n != a.size()) return false;
		for (int i = 0; i < n; i++) {
			if (!entries[i].equals(a.entries[i])) return false;
		}
		return true;
	}

	@Override
	public MapLeaf<K, V> mapEntries(Function<MapEntry<K, V>, MapEntry<K, V>> func) {
		MapEntry<K, V>[] newEntries = entries;
		for (int i = 0; i < entries.length; i++) {
			MapEntry<K, V> e = entries[i];
			MapEntry<K, V> newE = func.apply(e);
			if (e != newE) {
				if ((newE != null) && (!(e.keyEquals(newE))))
					throw new IllegalArgumentException("Function changed Key: " + e.getKey());
				if (newEntries == entries) {
					newEntries = entries.clone();
				}
				newEntries[i] = newE;
			}
		}
		if (newEntries == entries) return this;
		return create(newEntries);
	}

	@Override
	protected void validateWithPrefix(String prefix) throws InvalidDataException {
		validate();
		for (int i = 0; i < entries.length; i++) {
			MapEntry<K, V> e = entries[i];
			Hash h = e.getKeyRef().getHash();
			if (!h.toHexString().startsWith(prefix)) {
				throw new InvalidDataException("Prefix " + prefix + " invalid for map entry: " + e + " with hash: " + h,
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
		if (!isValidOrder(entries)) {
			throw new InvalidDataException("Invalid key ordering", this);
		}
	}

	@Override
	public boolean containsAllKeys(AHashMap<K, V> b) {
		if (this==b) return true;
		
		// if map is too big, can't possibly contain all keys
		if (b.count()>count) return false;
		
		// must be a mapleaf if this size or smaller
		return containsAllKeys((MapLeaf<K,V>)b);
	}
	
	protected boolean containsAllKeys(MapLeaf<K, V> b) {
		int ix=0;
		for (MapEntry<K,V> meb:b.entries) {
			Hash bh=meb.getKeyHash();
			
			if (ix>=count) return false; // no remaining entries in this
			while (ix<count) {
				MapEntry<K,V> mea=entries[ix];
				Hash ah=mea.getKeyHash();
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
	public byte getTag() {
		return Tag.MAP;
	}

	@Override
	public ACell toCanonical() {
		return this;
	}

	@Override
	public MapLeaf<K, V> slice(long start, long end) {
		if ((start<0)||(end>count)) return null;
		if (end<start) return null;
		int n=(int)(end-start);
		if (n==0) return Maps.empty();
		if (n==count) return this;

		@SuppressWarnings("unchecked")
		MapEntry<K,V>[] nrefs=new MapEntry[n];
		System.arraycopy(entries, (int) start, nrefs, 0, n);
		return new MapLeaf<K,V>(nrefs);
	}

}
