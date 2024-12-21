package convex.core.data;

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
	 * Maximum number of elements in a SetLeaf
	 * We use the same structure as a MapLeaf
	 */
	public static final int MAX_ELEMENTS = MapLeaf.MAX_ENTRIES;

	private final Ref<T>[] elements;

	SetLeaf(Ref<T>[] items) {
		super(items.length);
		elements = items;
	}

	/**
	 * Creates a SetLeaf with the specified elements. 
	 * 
	 * Null entries are ignored/removed.
	 * 
	 * @param elements Refs of Elements to include
	 * @return New ListMap
	 */
	@SafeVarargs
	public static <V extends ACell> SetLeaf<V> create(Ref<V>... elements) {
		return create(elements, 0, elements.length);
	}
	
	/**
	 * Create a SetLeaf with raw element Refs. Can create an invalid Cell, useful mainly for testing
	 * @param refs Refs to set elements, in desired order
	 * @return SetLeaf instance, possibly invalid
	 */
	@SuppressWarnings("unchecked")
	public static <V extends ACell> SetLeaf<V> unsafeCreate(Ref<V>... refs) {
		return new SetLeaf<V>(refs);
	}
	
	/**
	 * Create a SetLeaf with raw elements. Can create an invalid Cell, useful mainly for testing
	 * @param elements Elements to include in set, in desired order
	 * @return SetLeaf instance, possibly invalid
	 * 
	 */
	@SuppressWarnings("unchecked")
	public static <V extends ACell> SetLeaf<V> unsafeCreate(V... elements) {
		int n=elements.length;
		Ref<V>[] refs=new Ref[n];
		for (int i=0; i<n; i++) {
			refs[i]=Ref.get(elements[i]);
		}
		return unsafeCreate(refs);
	}
	
	/**
	 * Creates a SetLeaf with the specified elements. Null references are
	 * ignored/removed.
	 * 
	 * @param entries Refs to elements to include (some may be null)
	 * @param offset  Offset into entries array
	 * @param length  Number of entries to take from entries array, starting at
	 *                offset
	 * @return A new ListMap
	 */
	protected static <V extends ACell> SetLeaf<V> create(Ref<V>[] entries, int offset, int length) {
		if (length == 0) return Sets.empty();
		if (length > MAX_ELEMENTS) throw new IllegalArgumentException("Too many elements: " + entries.length);
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
	public Ref<T> getValueRef(ACell k) {
		// Use cached hash if available
		Hash h=Cells.cachedHash(k);
		if (h!=null) return getRefByHash(h);

		// linear scan if no hash for key?
		int len = size();
		for (int i = 0; i < len; i++) {
			Ref<T> e = elements[i];
			if (Cells.equals(k, e.getValue())) return e;
		}
		return null;
	}
	
	@Override
	public boolean containsHash(Hash hash) {
		return getRefByHash(hash) != null;
	}

	@Override
	protected Ref<T> getRefByHash(Hash hash) {
		int start =0;
		int end = size();
		while (end>start) { // binary search since we have hash
			int mid=(end+start)/2;
			Ref<T> e=elements[mid];
			Hash eh=e.getHash();
			int comp=(hash.compareTo(eh));
			if (comp==0) return e;
			if (comp<0) {
				end=mid; // first half
			} else {
				start=mid+1; // second half
			}
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
			if (Cells.equals(key, elements[i].getValue())) return i;
		}
		return -1;
	}
	
	/**
	 * Gets the index of key k in the internal array, or -1 if not found
	 * 
	 * @param key
	 * @return
	 */
	private int seekKeyRef(Hash h) {
		int start =0;
		int end = size();
		while (end>start) { // binary search since we have hash
			int mid=(end+start)/2;
			Hash eh=elements[mid].getHash();
			int comp=(h.compareTo(eh));
			if (comp==0) return mid;
			if (comp<0) {
				end=mid; // first half
			} else {
				start=mid+1; // second half
			}
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
	public SetLeaf<T> excludeHash(Hash hash) {
		int i = seekKeyRef(hash);
		if (i < 0) return this; // not found
		return excludeAt(i);
	}

	@SuppressWarnings("unchecked")
	private SetLeaf<T> excludeAt(int index) {
		int len = size();
		if (len == 1) return Sets.empty();
		Ref<T>[] newEntries = (Ref<T>[]) new Ref[len - 1];
		System.arraycopy(elements, 0, newEntries, 0, index);
		System.arraycopy(elements, index + 1, newEntries, index, len - index - 1);
		return new SetLeaf<T>(newEntries);
	}

	protected void accumulateValues(ArrayList<T> al) {
		for (int i = 0; i < elements.length; i++) {
			Ref<T> me = elements[i];
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
		pos = Format.writeVLQCount(bs,pos, n);

		for (int i = 0; i < n; i++) {
			pos = elements[i].encode(bs, pos);;
		}
		return pos;
	}

	@Override
	public int estimatedEncodingSize() {
		// allow space for header, size byte, 2 refs per entry
		return 2 + Format.MAX_EMBEDDED_LENGTH * size();
	}
	
	public static int MAX_ENCODING_LENGTH=  2 + MAX_ELEMENTS * Format.MAX_EMBEDDED_LENGTH;

	/**
	 * Reads a MapLeaf from the provided ByteBuffer Assumes the header byte is
	 * already read.
	 * 
	 * @param b Blob to read from
	 * @param pos Start position in Blob (location of tag byte)
	 * @param count Count of map elements	 
	 * @return New decoded instance
	 * @throws BadFormatException In the event of any encoding error
	 */
	
	public static <V extends ACell> SetLeaf<V> read(Blob b, int pos, long count) throws BadFormatException {
		int headerLen=1+1; // tag plus VLQ Count length which is always 1
		
		int epos=pos+headerLen;
		if (count == 0) return Sets.empty();
		if (count < 0) throw new BadFormatException("Negative count of set elements!");
		if (count > MAX_ELEMENTS) throw new BadFormatException("SetLeaf too big: " + count);
		
		@SuppressWarnings("unchecked")
		Ref<V>[] items = (Ref<V>[]) new Ref[(int) count];
		for (int i = 0; i < count; i++) {
			Ref<V> ref=Format.readRef(b,epos);
			epos+=ref.getEncodingLength();
			items[i]=ref;
		}
		if (!isValidOrder(items)) throw new BadFormatException("Set elements out of order in encoding");
		
		SetLeaf<V> result=new SetLeaf<V>(items);
		
		Blob enc=b.slice(pos, epos);
		result.attachEncoding(enc);
		return result;
	}

	

	@SuppressWarnings("unchecked")
	public static <V extends ACell> SetLeaf<V> emptySet() {
		return (SetLeaf<V>) Sets.EMPTY;
	}

	@Override
	public boolean isCanonical() {
		return true;
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
		return elements.length;
	}

	@SuppressWarnings("unchecked")
	@Override
	public Ref<T> getRef(int i) {
		Ref<T> e = elements[i]; // IndexOutOfBoundsException if out of range
		return e;
	}
	
	@Override
	public Ref<T> getElementRef(long i) {
		Ref<T> e = elements[Utils.checkedInt(i)]; // Exception if out of range
		return e;
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Override
	public SetLeaf updateRefs(IRefFunction func) {
		int n = elements.length;
		if (n == 0) return this;
		Ref<T>[] newEntries = elements;
		for (int i = 0; i < n; i++) {
			Ref<T> e = newEntries[i];
			Ref<T> newEntry = (Ref<T>) func.apply(e);
			if (e!=newEntry) {
				if (newEntries==elements) newEntries=elements.clone();
				newEntries[i]=newEntry;
			}
		}
		if (newEntries==elements) return this;
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
			Hash h = elements[i].getHash();
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
				newEntries[ix++] = elements[i];
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
			Ref<T> ae = (ai < al) ? this.elements[ai] : null;
			Ref<T> be = (bi < bl) ? b.elements[bi] : null;
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
				results = new ArrayList<>(2*MAX_ELEMENTS); // space for all if needed
				// include new entries
				for (int i = 0; i < ai; i++) {
					results.add(elements[i]);
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
			result = func.apply(result, elements[i].getValue());
		}
		return result;
	}

	@SuppressWarnings("unchecked")
	@Override
	public boolean equals(ACell a) {
		if (!(a instanceof SetLeaf)) return false;
		return equals((SetLeaf<T>) a);
	}

	public boolean equals(SetLeaf<T> a) {
		if (this == a) return true;
		if (count != a.count) return false;
		int n = (int)count;
		for (int i = 0; i < n; i++) {
			if (!elements[i].equals(a.elements[i])) return false;
		}
		return true;
	}
	
	@Override
	public void validate() throws InvalidDataException {
		super.validate();
		validateWithPrefix(Hash.EMPTY_HASH,0,-1);
	}

	@Override
	protected void validateWithPrefix(Hash prefix, int digit, int position) throws InvalidDataException {
		if (!isValidOrder(elements)) {
			throw new InvalidDataException("Bad ordering of set elements!",this);
		}
		
		for (int i = 0; i < elements.length; i++) {
			Ref<T> e = elements[i];
			Hash h = e.getHash();
			long match=h.hexMatch(prefix);
			if (match<(position-1)) {
				throw new InvalidDataException("Parent prefix did not match",this);
			}
			if (position>=0) {
				int mydigit=h.getHexDigit(position);
				if (mydigit!=digit) {
					throw new InvalidDataException("Bad hex digit at position: "+position,this);
				}
			}
			e.validate();

		}
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if ((count == 0) && (this != Sets.EMPTY)) {
			throw new InvalidDataException("Empty set not using canonical instance", this);
		}

		if (count > MAX_ELEMENTS) {
			throw new InvalidDataException("Too many items in SetLeaf: " + elements.length, this);
		}

		// validates both key uniqueness and sort order
		if (!isValidOrder(elements)) throw new InvalidDataException("Bad ordering of set elements",this);

	}

	@Override
	public boolean containsAll(ASet<?> b) {
		if (this==b) return true;
		
		// if set is too big, can't possibly contain all keys
		if (b.count()>count) return false;
		
		// must be a setleaf if this size or smaller
		return containsAll((SetLeaf<?>)b);
	}
	
	@Override
	public boolean isSubset(ASet<? super T> b) {
		return b.containsAll(this);
	}
	
	protected boolean containsAll(SetLeaf<?> b) {
		int ix=0;
		for (Ref<?> meb:b.elements) {
			Hash bh=meb.getHash();
			
			if (ix>=count) return false; // no remaining entries in this
			while (ix<count) {
				Ref<T> mea=elements[ix];
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
	public AHashSet<T> includeRef(Ref<T> e) {
		int n=elements.length;
		Hash h=e.getHash();
		int pos=0;
		for (; pos<n; pos++) {
			Ref<T> iref=elements[pos];
			int c=h.compareTo(iref.getHash());
			if (c==0) return this;
			if (c<0) break; // need to add at this position
		}
	
		// New element must be added at pos
		@SuppressWarnings("unchecked")
		Ref<T>[] newEntries=new Ref[n+1];
		System.arraycopy(elements, 0, newEntries, 0, pos);
		System.arraycopy(elements, pos, newEntries, pos+1, n-pos);
		newEntries[pos]=e;
		
		if (n<MAX_ELEMENTS) {
			// New leaf if n (current elements) is less than the maximum size
			return new SetLeaf<T>(newEntries);
		} else {
			// Maximum size exceeded, so need to expand to tree. 
			// Shift required since this might not be the tree root!
			return SetTree.create(newEntries);
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
		return elements[Utils.checkedInt(index)].getValue();
	}

	@Override
	public AHashSet<T> toCanonical() {
		if (count<=MAX_ELEMENTS) return this;
		return SetTree.create(elements);
	}

	@SuppressWarnings("unchecked")
	@Override
	public ASet<T> slice(long start, long end) {
		if (start<0) return null;
		if (end>count) return null;
		int n=(int)(end-start);
		if (n==count) return this;
		if (n==0) return empty();
		Ref<T>[] nrefs=new Ref[n];
		System.arraycopy(elements, (int) start, nrefs, 0, n);
		return new SetLeaf<T>(nrefs);
	}

	@Override
	protected Hash getFirstHash() {
		if (count==0) return null;
		return elements[0].getHash();
	}





}
