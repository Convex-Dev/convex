package convex.core.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.TODOException;
import convex.core.lang.RT;
import convex.core.exceptions.Panic;
import convex.core.util.Bits;
import convex.core.util.MergeFunction;
import convex.core.util.Utils;

/**
 * Persistent Map for large hash maps requiring tree structure.
 * 
 * Internally implemented as a radix tree, indexed by key hash. Uses an array of
 * child Maps, with a bitmap mask indicating which hex digits are present, i.e.
 * have non-empty children.
 *
 * @param <K> Type of map keys
 * @param <V> Type of map values
 */
public class MapTree<K extends ACell, V extends ACell> extends AHashMap<K, V> {
	/**
	 * Child maps, one for each present bit in the mask, max 16
	 */
	private final Ref<AHashMap<K, V>>[] children;
	
	private static final int FANOUT=16;

	/**
	 * Shift position of this treemap node in number of hex digits
	 */
	private final int shift;

	/**
	 * Mask indicating which hex digits are present in the child array e.g. 0x0001
	 * indicates all children are in the '0' digit. e.g. 0xFFFF indicates there are
	 * children for every digit.
	 */
	private final short mask;

	private MapTree(Ref<AHashMap<K, V>>[] children, int shift, short mask, long count) {
		super(count);
		this.children = children;
		this.shift = shift;
		this.mask = mask;
	}

	/**
	 * Computes the total count from an array of Refs to maps Ignores null Refs in
	 * child array
	 * 
	 * @param children
	 * @return The total count of all child maps
	 */
	private static <K extends ACell, V extends ACell> long computeCount(Ref<AHashMap<K, V>>[] children) {
		long n = 0;
		for (Ref<AHashMap<K, V>> cref : children) {
			if (cref == null) continue;
			AMap<K, V> m = cref.getValue();
			n += m.count();
		}
		return n;
	}
	
	/**
	 * Create MapTree with specific children at specified shift level
	 * Children must branch at the given shift level
	 */
	@SafeVarargs
	static <K extends ACell, V extends ACell> MapTree<K, V> create(int shift, AHashMap<K,V> ... children) {
		int n=children.length;
		Arrays.sort(children,shiftComparator(shift));
		@SuppressWarnings("unchecked")
		Ref<AHashMap<K,V>>[] rs=new Ref[n];
		long count=0;
		short mask=0;
		for (int i=0; i<n; i++) {
			AHashMap<K,V> child=children[i];
			rs[i]=Ref.get(child);
			count+=child.count;
			int digit=child.getFirstHash().getHexDigit(shift);
			mask|=(1<<digit);
		}
		if (Integer.bitCount(mask&0xFFFF)!=n) {
			throw new IllegalArgumentException("Children do not differ at specified digit");
		}
		return new MapTree<>(rs,shift,mask,count);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	static Comparator<AHashMap>[] COMPARATORS=new Comparator[64];
	
	@SuppressWarnings("rawtypes")
	private static Comparator<AHashMap> shiftComparator(int shift) {
		if (COMPARATORS[shift]==null) {
			COMPARATORS[shift]=new Comparator<AHashMap>() {
				@Override
				public int compare(AHashMap o1, AHashMap o2) {
					int d1= o1.getFirstHash().getHexDigit(shift);
					int d2= o2.getFirstHash().getHexDigit(shift);
					return d1-d2;
				}
			};
		};
		return COMPARATORS[shift];
	}

	// Used to promote a MapLeaf to a MapTree
	@SuppressWarnings("unchecked")
	static <K extends ACell, V extends ACell> MapTree<K, V> create(MapEntry<K, V>[] newEntries) {
		int n = newEntries.length;
		if (n <= MapLeaf.MAX_ENTRIES) {
			throw new IllegalArgumentException(
					"Insufficient distinct entries for TreeMap construction: " + newEntries.length);
		}

		int shift=computeShift(newEntries);
		// construct full child array
		Ref<AHashMap<K, V>>[] children = new Ref[FANOUT];
		for (int i = 0; i < n; i++) {
			MapEntry<K, V> e = newEntries[i];
			int ix = e.getKeyHash().getHexDigit(shift);
			Ref<AHashMap<K, V>> ref = children[ix];
			if (ref == null) {
				children[ix] = MapLeaf.create(e).getRef();
			} else {
				AHashMap<K, V> newChild=ref.getValue().assocEntry(e);
				children[ix] = newChild.getRef();
			}
		}
		return (MapTree<K, V>) createFull(children, shift);
	}
	
	/**
	 * Computes the common shift for a vector of entries.
	 * This is the shift at which the first split occurs, i.e length of common prefix
	 * @param es Entries
	 * @return
	 */
	protected static <K extends ACell, V extends ACell> int computeShift(MapEntry<K,V>[] es) {
		int shift=63; // max possible
		Hash h=es[0].getKeyHash();
		int n=es.length;
		for (int i=1; i<n; i++) {
			shift=Math.min(shift, h.commonHexPrefixLength(es[i].getKeyHash(),shift));
		}
		return shift;
	}

	/**
	 * Creates a Tree map given child refs for each digit
	 * 
	 * @param children An array of children, may refer to nulls or empty maps which
	 *                 will be filtered out
	 * @return
	 */
	private static <K extends ACell, V extends ACell> AHashMap<K, V> createFull(Ref<AHashMap<K, V>>[] children, int shift, long count) {
		if (children.length != FANOUT) throw new IllegalArgumentException("16 children required!");
		int mask=0;
		for (int i=0; i<FANOUT; i++) {
			Ref<AHashMap<K, V>> ch=children[i];
			if (ch!=null) {
				AMap<K, V> m = ch.getValue();
				if ((m!=null)&&(!m.isEmpty())) {
					mask|=(1<<i);
				}
			}
		}
		if (mask==0xFFFF) return create(children, shift, (short) 0xFFFF, count);
		Ref<AHashMap<K, V>>[] newChildren = Refs.filterSmallArray(children, mask);
		return create(newChildren, shift, (short)mask, count);
	}

	/**
	 * Create a MapTree with a full complement of children.
	 * @param <K>
	 * @param <V>
	 * @param newChildren
	 * @param shift
	 * @return
	 */
	private static <K extends ACell, V extends ACell> AHashMap<K, V> createFull(Ref<AHashMap<K, V>>[] newChildren, int shift) {
		return createFull(newChildren, shift, computeCount(newChildren));
	}

	/**
	 * Creates a Map with the specified child map Refs. Removes empty maps passed as
	 * children.
	 * 
	 * Returns a MapLeaf for small maps.
	 * 
	 * @param children Array of Refs to child maps for each bit in mask
	 * @param shift    Shift position (hex digit of key hashes for this map)
	 * @param mask     Mask specifying the hex digits included in the child array at
	 *                 this shift position
	 * @return A new map as specified @
	 */
	@SuppressWarnings("unchecked")
	private static <K extends ACell, V extends ACell> AHashMap<K, V> create(Ref<AHashMap<K, V>>[] children, int shift, short mask, long count) {
		int cLen = children.length;
		if (Integer.bitCount(mask & 0xFFFF) != cLen) {
			throw new IllegalArgumentException(
					"Invalid child array length " + cLen + " for bit mask " + Utils.toHexString(mask));
		}

		// compress small counts to ListMap
		if (count <= MapLeaf.MAX_ENTRIES) {
			MapEntry<K, V>[] entries = new MapEntry[Utils.checkedInt(count)];
			int ix = 0;
			for (Ref<AHashMap<K, V>> childRef : children) {
				AMap<K, V> child = childRef.getValue();
				long cc = child.count();
				for (long i = 0; i < cc; i++) {
					entries[ix++] = child.entryAt(i);
				}
			}
			assert (ix == count);
			return MapLeaf.create(entries);
		}
		int sel = (1 << cLen) - 1;
		short newMask = mask;
		for (int i = 0; i < cLen; i++) {
			AMap<K, V> child = children[i].getValue();
			if (child.isEmpty()) {
				newMask = (short) (newMask & ~(1 << digitForIndex(i, mask))); // remove from mask
				sel = sel & ~(1 << i); // remove from selection
			}
		}
		if (mask != newMask) {
			return new MapTree<K, V>(Refs.filterSmallArray(children, sel), shift, newMask, count);
		}
		return new MapTree<K, V>(children, shift, mask, count);
	}

	@Override
	public MapEntry<K, V> getEntry(ACell k) {
		return getKeyRefEntry(Ref.get(k));
	}

	@Override
	public MapEntry<K, V> getKeyRefEntry(Ref<ACell> ref) {
		Hash h=ref.getHash();
		int digit = h.getHexDigit(shift);
		int i = Bits.indexForDigit(digit, mask);
		if (i < 0) return null; // -1 case indicates not found
		return children[i].getValue().getKeyRefEntry(ref);
	}

	@Override
	public boolean containsValue(ACell value) {
		for (Ref<AHashMap<K, V>> b : children) {
			if (b.getValue().containsValue(value)) return true;
		}
		return false;
	}

	@Override
	public V get(ACell key) {
		MapEntry<K, V> me = getKeyRefEntry(Ref.get(key));
		if (me == null) return null;
		return me.getValue();
	}

	@Override
	public MapEntry<K, V> entryAt(long i) {
		long pos = i;
		for (Ref<AHashMap<K, V>> c : children) {
			AHashMap<K, V> child = c.getValue();
			long cc = child.count();
			if (pos < cc) return child.entryAt(pos);
			pos -= cc;
		}
		throw new IndexOutOfBoundsException((int)i);
	}

	@Override
	protected MapEntry<K, V> getEntryByHash(Hash hash) {
		int digit = hash.getHexDigit(shift);
		int i = Bits.indexForDigit(digit, mask);
		if (i < 0) return null; // not present
		return children[i].getValue().getEntryByHash(hash);
	}

	@Override
	public AHashMap<K, V> dissoc(ACell key) {
		return dissocHash(Cells.getHash(key));
	}

	@Override
	@SuppressWarnings("unchecked")
	public AHashMap<K, V> dissocHash(Hash keyHash) {
		int digit = keyHash.getHexDigit(shift);
		int i = Bits.indexForDigit(digit, mask);
		if (i < 0) return this; // not present

		// dissoc entry from child
		AHashMap<K, V> child = children[i].getValue();
		AHashMap<K, V> newChild = child.dissocHash(keyHash);
		if (child == newChild) return this; // no removal, no change

		if (count - 1 == MapLeaf.MAX_ENTRIES) {
			// reduce to a ListMap
			ArrayList<Entry<K, V>> eset = new ArrayList<>();
			for (int j=0; j<children.length; j++) {
				AHashMap<K, V> c = (i==j)?newChild:children[j].getValue();
				c.accumulateEntries(eset);
			}
			if (!(eset.size()==MapLeaf.MAX_ENTRIES)) {
				throw new Panic("Expected to remove at least one entry!");
			}
			return MapLeaf.create(eset.toArray((MapEntry<K, V>[]) MapLeaf.EMPTY_ENTRIES));
		} else {
			// replace child
			if (newChild.isEmpty()) return dissocChild(i);
			return replaceChild(i, newChild.getRef());
		}
	}

	@SuppressWarnings("unchecked")
	private AHashMap<K, V> dissocChild(int i) {
		int bsize = children.length;
		if (bsize==2) {
			// just return other child!
			return children[1-i].getValue();
		}
		AHashMap<K, V> child = children[i].getValue();
		Ref<AHashMap<K, V>>[] newChildren = (Ref<AHashMap<K, V>>[]) new Ref<?>[bsize - 1];
		System.arraycopy(children, 0, newChildren, 0, i);
		System.arraycopy(children, i + 1, newChildren, i, bsize - i - 1);
		short newMask = (short) (mask & (~(1 << digitForIndex(i, mask))));
		long newCount = count - child.count();
		return create(newChildren, shift, newMask, newCount);
	}

	@SuppressWarnings("unchecked")
	private MapTree<K, V> insertChild(int digit, Ref<AHashMap<K, V>> newChild) {
		int bsize = children.length;
		int i = Bits.positionForDigit(digit, mask);
		short newMask = (short) (mask | (1 << digit));
		if (mask == newMask) throw new Panic("Digit already present!");

		Ref<AHashMap<K, V>>[] newChildren = (Ref<AHashMap<K, V>>[]) new Ref<?>[bsize + 1];
		System.arraycopy(children, 0, newChildren, 0, i);
		System.arraycopy(children, i, newChildren, i + 1, bsize - i);
		newChildren[i] = newChild;
		long newCount = count + newChild.getValue().count();
		return (MapTree<K, V>) create(newChildren, shift, newMask, newCount);
	}

	/**
	 * Replaces the child ref at a given index position. Will return the same
	 * TreeMap if no change
	 * 
	 * @param i
	 * @param newChild
	 * @return @
	 */
	private MapTree<K, V> replaceChild(int i, Ref<AHashMap<K, V>> newChild) {
		if (children[i] == newChild) return this;
		AHashMap<K, V> oldChild = children[i].getValue();
		Ref<AHashMap<K, V>>[] newChildren = children.clone();
		newChildren[i] = newChild;
		long newCount = count + newChild.getValue().count() - oldChild.count();
		return (MapTree<K, V>) create(newChildren, shift, mask, newCount);
	}

	public static int digitForIndex(int index, short mask) {
		// scan mask for specified index
		int found = 0;
		for (int i = 0; i < FANOUT; i++) {
			if ((mask & (1 << i)) != 0) {
				if (found++ == index) return i;
			}
		}
		throw new IllegalArgumentException("Index " + index + " not available in mask map: " + Utils.toHexString(mask));
	}

	@SuppressWarnings("unchecked")
	@Override
	public MapTree<K, V> assoc(ACell key, ACell value) {
		K k= (K)key;
		Ref<K> keyRef = Ref.get(k);
		return assocRef(keyRef, (V) value);
	}

	@Override
	public MapTree<K, V> assocRef(Ref<K> keyRef, V value) {
		Hash kh= keyRef.getHash();
		int shift= kh.commonHexPrefixLength(getFirstHash(), this.shift);
		if (shift<this.shift) {
			// branch at an earlier position
			MapLeaf<K,V> newLeaf=MapLeaf.create(MapEntry.fromRefs(keyRef, Ref.get(value)));
			return create(shift,newLeaf,this);
		}
		
		int digit=kh.getHexDigit(shift);
		int i = Bits.indexForDigit(digit, mask);
		if (i < 0) {
			// location not present, need to insert new child
			AHashMap<K, V> newChild = MapLeaf.create(MapEntry.fromRefs(keyRef, Ref.get(value)));
			return insertChild(digit, newChild.getRef());
		} else {
			// child exists, so assoc in new ref at lower shift level
			AHashMap<K, V> child = children[i].getValue();
			AHashMap<K, V> newChild = child.assocRef(keyRef, value);
			return replaceChild(i, newChild.getRef());
		}
	}

	@Override
	public AHashMap<K, V> assocEntry(MapEntry<K, V> e) {
		Hash kh= e.getKeyHash();
		int shift= kh.commonHexPrefixLength(getFirstHash(), this.shift);
		if (shift<this.shift) {
			// branch at an earlier position
			MapLeaf<K,V> newLeaf=MapLeaf.create(e);
			return create(shift,newLeaf,this);
		}

		assert (this.shift == shift); // should always be correct shift
		Ref<K> keyRef = e.getKeyRef();
		int digit = keyRef.getHash().getHexDigit(shift);
		int i = Bits.indexForDigit(digit, mask);
		if (i < 0) {
			// location not present
			AHashMap<K, V> newChild = MapLeaf.create(e);
			return insertChild(digit, newChild.getRef());
		} else {
			// location needs update
			AHashMap<K, V> child = children[i].getValue();
			AHashMap<K, V> newChild = child.assocEntry(e);
			if (child == newChild) return this;
			return replaceChild(i, newChild.getRef());
		}
	}

	@Override
	public Set<K> keySet() {
		int len = size();
		HashSet<K> h = new HashSet<K>(len);
		accumulateKeySet(h);
		return h;
	}

	@Override
	protected void accumulateKeySet(Set<K> h) {
		for (Ref<AHashMap<K, V>> mr : children) {
			mr.getValue().accumulateKeySet(h);
		}
	}

	@Override
	protected void accumulateValues(java.util.List<V> al) {
		for (Ref<AHashMap<K, V>> mr : children) {
			mr.getValue().accumulateValues(al);
		}
	}

	@Override
	protected void accumulateEntries(Collection<Entry<K, V>> h) {
		for (Ref<AHashMap<K, V>> mr : children) {
			mr.getValue().accumulateEntries(h);
		}
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.MAP;
		return encodeRaw(bs,pos);
	}

	
	@Override
	public int encodeRaw(byte[] bs, int pos) {
		int ilength = children.length;
		pos = Format.writeVLQCount(bs,pos, count); 
		
		bs[pos++] = (byte) shift;
		pos = Utils.writeShort(bs, pos,mask);

		for (int i = 0; i < ilength; i++) {
			pos = children[i].encode(bs,pos);
		}
		return pos;
	}

	@Override
	public int estimatedEncodingSize() {
		// allow space for tag, shift byte byte, 2 byte mask, embedded child refs
		return 4 + Format.MAX_EMBEDDED_LENGTH * children.length;
	}
	
	/**
	 * Max length is tag, shift byte, 2 byte mask, max count plus embedded Refs
	 */
	public static int MAX_ENCODING_LENGTH = 4 + Format.MAX_EMBEDDED_LENGTH * FANOUT;

	/**
	 * Reads a ListMap from the provided Blob 
	 * 
	 * @param b Blob to read from
	 * @param pos Start position in Blob (location of tag byte)
	 * @param count Count of map entries* 
	 * @return New decoded instance
	 * @throws BadFormatException In the event of any encoding error
	 */
	@SuppressWarnings("unchecked")
	public static <K extends ACell, V extends ACell> MapTree<K, V> read(Blob b, int pos, long count) throws BadFormatException {
		int epos=pos+1+Format.getVLQCountLength(count);
		int shift=b.byteAt(epos);
		short mask=b.shortAt(epos+1);
		epos+=3;

		int ilength = Integer.bitCount(mask & 0xFFFF);
		Ref<AHashMap<K, V>>[] blocks = (Ref<AHashMap<K, V>>[]) new Ref<?>[ilength];

		for (int i = 0; i < ilength; i++) {
			// need to read as a Ref
			Ref<AHashMap<K, V>> ref = Format.readRef(b,epos);
			epos+=ref.getEncodingLength();
			blocks[i] = ref;
		}
		// create directly, we have all values
		MapTree<K, V> result = new MapTree<K, V>(blocks, shift, mask, count);
		if (!result.isValidStructure()) throw new BadFormatException("Problem with TreeMap invariants");
		result.attachEncoding(b.slice(pos, epos));
		return result;
	}

	@Override
	public void forEach(BiConsumer<? super K, ? super V> action) {
		for (Ref<AHashMap<K, V>> sub : children) {
			sub.getValue().forEach(action);
		}
	}

	@Override
	public boolean isCanonical() {
		if (count <= MapLeaf.MAX_ENTRIES) return false;
		return true;
	}

	@Override
	public int getRefCount() {
		return children.length;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <R extends ACell> Ref<R> getRef(int i) {
		return (Ref<R>) children[i];
	}

	@SuppressWarnings("unchecked")
	@Override
	public MapTree<K,V> updateRefs(IRefFunction func) {
		int n = children.length;
		if (n == 0) return this;
		Ref<AHashMap<K, V>>[] newChildren = children;
		for (int i = 0; i < n; i++) {
			Ref<AHashMap<K, V>> child = children[i];
			Ref<AHashMap<K, V>> newChild = (Ref<AHashMap<K, V>>) func.apply(child);
			if (child != newChild) {
				if (children == newChildren) {
					newChildren = children.clone();
				}
				newChildren[i] = newChild;
			}
		}
		if (newChildren == children) return this;
		// Note: we assume no key hashes have changed, so structure is the same
		MapTree<K,V> result= new MapTree<>(newChildren, shift, mask, count);
		result.attachEncoding(encoding); // this is an optimisation to avoid re-encoding
		return result;
	}

	@Override
	public AHashMap<K, V> mergeWith(AHashMap<K, V> b, MergeFunction<V> func) {
		return mergeWith(b, func, this.shift);
	}

	@Override
	protected AHashMap<K, V> mergeWith(AHashMap<K, V> b, MergeFunction<V> func, int shift) {
		if ((b instanceof MapTree)) {
			MapTree<K, V> bt = (MapTree<K, V>) b;
			if (this.shift != bt.shift) throw new Panic("Misaligned shifts!");
			return mergeWith(bt, func, shift);
		}
		if ((b instanceof MapLeaf)) return mergeWith((MapLeaf<K, V>) b, func, shift);
		throw new Panic("Unrecognised map type: " + b.getClass());
	}

	@SuppressWarnings("unchecked")
	private AHashMap<K, V> mergeWith(MapTree<K, V> b, MergeFunction<V> func, int shift) {
		// assume two TreeMaps with identical prefix and shift
		assert (b.shift == shift);
		int fullMask = mask | b.mask;
		// We are going to build full child list only if needed
		Ref<AHashMap<K, V>>[] newChildren = null;
		for (int digit = 0; digit < FANOUT; digit++) {
			int bitMask = 1 << digit;
			if ((fullMask & bitMask) == 0) continue; // nothing to merge at this index
			AHashMap<K, V> ac = childForDigit(digit).getValue();
			AHashMap<K, V> bc = b.childForDigit(digit).getValue();
			AHashMap<K, V> rc = ac.mergeWith(bc, func, shift + 1);
			if (ac != rc) {
				if (newChildren == null) {
					newChildren = (Ref<AHashMap<K, V>>[]) new Ref<?>[FANOUT];
					for (int ii = 0; ii < digit; ii++) { // copy existing children up to this point
						int chi = Bits.indexForDigit(ii, mask);
						if (chi >= 0) newChildren[ii] = children[chi];
					}
				}
			}
			if (newChildren != null) newChildren[digit] = rc.getRef();
		}
		if (newChildren == null) return this;
		return createFull(newChildren, shift);
	}

	@SuppressWarnings("unchecked")
	private AHashMap<K, V> mergeWith(MapLeaf<K, V> b, MergeFunction<V> func, int shift) {
		Ref<AHashMap<K, V>>[] newChildren = null;
		int ix = 0;
		for (int i = 0; i < FANOUT; i++) {
			int imask = (1 << i); // mask for this digit
			if ((mask & imask) == 0) continue;
			Ref<AHashMap<K, V>> cref = children[ix++];
			AHashMap<K, V> child = cref.getValue();
			MapLeaf<K, V> bSubset = b.filterHexDigits(shift, imask); // filter only relevant elements in b
			AHashMap<K, V> newChild = child.mergeWith(bSubset, func, shift + 1);
			if (child != newChild) {
				if (newChildren == null) {
					newChildren = (Ref<AHashMap<K, V>>[]) new Ref<?>[16];
					for (int ii = 0; ii < children.length; ii++) { // copy existing children
						int chi = digitForIndex(ii, mask);
						newChildren[chi] = children[ii];
					}
				}
			}
			if (newChildren != null) {
				newChildren[i] = newChild.getRef();
			}
		}
		assert (ix == children.length);
		// if any new children created, create a new Map, else use this
		AHashMap<K, V> result = (newChildren == null) ? this : createFull(newChildren, shift);

		MapLeaf<K, V> extras = b.filterHexDigits(shift, ~mask);
		int en = extras.size();
		for (int i = 0; i < en; i++) {
			MapEntry<K, V> e = extras.entryAt(i);
			V value = func.merge(null, e.getValue());
			if (value != null) {
				// include only new keys where function result is not null. Re-use existing
				// entry if possible.
				result = result.assocEntry(e.withValue(value));
			}
		}
		return result;
	}

	@Override
	public AHashMap<K, V> mergeDifferences(AHashMap<K, V> b, MergeFunction<V> func) {
		return mergeDifferences(b, func,0);
	}
	
	@Override
	protected AHashMap<K, V> mergeDifferences(AHashMap<K, V> b, MergeFunction<V> func,int shift) {
		if ((b instanceof MapTree)) {
			MapTree<K, V> bt = (MapTree<K, V>) b;
			// this is OK, top levels should both have shift 0 and be aligned down the tree.
			if (this.shift != bt.shift) throw new Panic("Misaligned shifts!");
			return mergeDifferences(bt, func,shift);
		} else {
			// must be ListMap
			return mergeDifferences((MapLeaf<K, V>) b, func,shift);
		}
	}

	@SuppressWarnings("unchecked")
	private AHashMap<K, V> mergeDifferences(MapTree<K, V> b, MergeFunction<V> func, int shift) {
		// assume two MapTrees with identical prefix and shift
		if (this.equals(b)) return this; // no differences to merge
		int fullMask = mask | b.mask;
		Ref<AHashMap<K, V>>[] newChildren = null; // going to build new full child list if needed
		for (int i = 0; i < FANOUT; i++) {
			int bitMask = 1 << i;
			if ((fullMask & bitMask) == 0) continue; // nothing to merge at this index
			Ref<AHashMap<K, V>> aref = childForDigit(i);
			Ref<AHashMap<K, V>> bref = b.childForDigit(i);
			if (aref.equals(bref)) continue; // identical children, no differences
			AHashMap<K, V> ac = aref.getValue();
			AHashMap<K, V> bc = bref.getValue();
			AHashMap<K, V> newChild = ac.mergeDifferences(bc, func,shift+1);
			if (newChild != ac) {
				if (newChildren == null) {
					newChildren = (Ref<AHashMap<K, V>>[]) new Ref<?>[16];
					for (int ii = 0; ii < 16; ii++) { // copy existing children
						int chi = Bits.indexForDigit(ii, mask);
						if (chi >= 0) newChildren[ii] = children[chi];
					}
				}
			}
			if (newChildren != null) newChildren[i] = (newChild == bc) ? bref : newChild.getRef();
		}
		if (newChildren == null) return this;
		return createFull(newChildren, shift);
	}

	@SuppressWarnings("unchecked")
	private AHashMap<K, V> mergeDifferences(MapLeaf<K, V> b, MergeFunction<V> func, int shift) {
		Ref<AHashMap<K, V>>[] newChildren = null;
		int ix = 0;
		for (int i = 0; i < FANOUT; i++) {
			int imask = (1 << i); // mask for this digit
			if ((mask & imask) == 0) continue;
			Ref<AHashMap<K, V>> cref = children[ix++];
			AHashMap<K, V> child = cref.getValue();
			MapLeaf<K, V> bSubset = b.filterHexDigits(shift, imask); // filter only relevant elements in b
			AHashMap<K, V> newChild = child.mergeDifferences(bSubset, func,shift+1);
			if (child != newChild) {
				if (newChildren == null) {
					newChildren = (Ref<AHashMap<K, V>>[]) new Ref<?>[16];
					for (int ii = 0; ii < children.length; ii++) { // copy existing children
						int chi = digitForIndex(ii, mask);
						newChildren[chi] = children[ii];
					}
				}
			}
			if (newChildren != null) newChildren[i] = newChild.getRef();
		}
		assert (ix == children.length);
		AHashMap<K, V> result = (newChildren == null) ? this : createFull(newChildren, shift);

		MapLeaf<K, V> extras = b.filterHexDigits(shift, ~mask);
		int en = extras.size();
		for (int i = 0; i < en; i++) {
			MapEntry<K, V> e = extras.entryAt(i);
			V value = func.merge(null, e.getValue());
			if (value != null) {
				// include only new keys where function result is not null. Re-use existing
				// entry if possible.
				result = result.assocEntry(e.withValue(value));
			}
		}
		return result;
	}

	/**
	 * Gets the Ref for the child at the given digit, or an empty map if not found
	 * 
	 * @param digit The hex digit to query at this TreeMap's shift position
	 * @return The child map for this digit, or an empty map if the child does not
	 *         exist
	 */
	private Ref<AHashMap<K, V>> childForDigit(int digit) {
		int ix = Bits.indexForDigit(digit, mask);
		if (ix < 0) return Maps.emptyRef();
		return children[ix];
	}

	@Override
	public <R> R reduceValues(BiFunction<? super R, ? super V, ? extends R> func, R initial) {
		int n = children.length;
		R result = initial;
		for (int i = 0; i < n; i++) {
			result = children[i].getValue().reduceValues(func, result);
		}
		return result;
	}

	@Override
	public <R> R reduceEntries(BiFunction<? super R, MapEntry<K, V>, ? extends R> func, R initial) {
		int n = children.length;
		R result = initial;
		for (int i = 0; i < n; i++) {
			result = children[i].getValue().reduceEntries(func, result);
		}
		return result;
	}

	@SuppressWarnings("unchecked")
	@Override
	public boolean equals(ACell a) {
		if (!(a instanceof MapTree)) return false;
		return equals((MapTree<K, V>) a);
	}

	boolean equals(MapTree<K, V> b) {
		if (b==null) return false;
		if (this == b) return true;
		long n = count;
		if (n != b.count) return false;
		if (mask != b.mask) return false;
		if (shift != b.shift) return false;

		// Fall back to comparing hashes. Probably most efficient in general.
		if (getHash().equals(b.getHash())) return true;
		return false;
	}

	@Override
	public AHashMap<K, V> mapEntries(Function<MapEntry<K, V>, MapEntry<K, V>> func) {
		int n = children.length;
		if (n == 0) return this;
		Ref<AHashMap<K, V>>[] newChildren = children;
		for (int i = 0; i < n; i++) {
			AHashMap<K, V> child = children[i].getValue();
			AHashMap<K, V> newChild = child.mapEntries(func);
			if (child != newChild) {
				if (children == newChildren) {
					newChildren = children.clone();
				}
				newChildren[i] = newChild.getRef();
			}
		}
		if (newChildren == children) return this;

		// Note: creation should remove any empty children. Need to recompute count
		// since
		// entries may have been removed.
		return create(newChildren, shift, mask, computeCount(newChildren));
	}

	@Override
	public void validate() throws InvalidDataException {
		super.validate();
		
		try {
			Hash firstHash=getFirstHash();
			// Perform child validation
			validateWithPrefix(firstHash,shift);
		} catch (ClassCastException e) {
			throw new InvalidDataException("Can't get first hash of map: "+e.getMessage(),e);
		}
	}

	@Override
	protected void validateWithPrefix(Hash prefix, int shift) throws InvalidDataException {
		if (mask == 0) throw new InvalidDataException("TreeMap must have children!", this);
		int bsize = children.length;
		if (bsize<2) {
			throw new InvalidDataException("Non-canonical MapTree with child count "+bsize,this);
		}

		long childCount=0;;
		for (int i = 0; i < bsize; i++) {
			if (children[i] == null)
				throw new InvalidDataException("Null child ref at " + prefix + Utils.toHexChar(digitForIndex(i, mask)),
						this);
			ACell o = children[i].getValue();
			if (!(o instanceof AHashMap)) {
				throw new InvalidDataException(
						"Expected AHashMap child at " + prefix + Utils.toHexChar(digitForIndex(i, mask)), this);
			}
			AHashMap<K, V> child = RT.ensureHashMap(o);
			if (child==null) {
				throw new InvalidDataException("Non-hashmap child at position "+i,this);
			}
			if (child.isEmpty())
				throw new InvalidDataException("Empty child at " + prefix + Utils.toHexChar(digitForIndex(i, mask)),
						this);
			int d = digitForIndex(i, mask);
			Hash ch=child.getFirstHash();
			if (ch.getHexDigit(shift)!=d) {
				throw new InvalidDataException("Wrong child digit at position "+i,this);
			}
			if (prefix.commonHexPrefixLength(ch, Hash.HEX_LENGTH)<shift) {
				throw new InvalidDataException("Inconsistent child at position "+i,this);
			}
			if (child instanceof MapLeaf) {
				child.validateWithPrefix(ch, shift+1);
			}
			
			childCount += child.count();
		}
		
		if (count != childCount) {
			throw new InvalidDataException("Bad child count, expected " + count + " but children had: " + childCount, this);
		}
	}

	private boolean isValidStructure() {
		if (count <= MapLeaf.MAX_ENTRIES) return false;
		if (children.length != Integer.bitCount(mask & 0xFFFF)) return false;
		for (int i = 0; i < children.length; i++) {
			if (children[i] == null) return false;
		}
		return true;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (!isValidStructure()) throw new InvalidDataException("Bad structure", this);
	}

	@SuppressWarnings("unchecked")
	@Override
	public boolean containsAllKeys(AHashMap<K, V> map) {
		if (map instanceof MapTree) {
			return containsAllKeys((MapTree<K,V>)map);
		}
		// must be a MapLeaf
		long n=map.count;
		for (long i=0; i<n; i++) {
			MapEntry<K,V> me=map.entryAt(i);
			if (!this.containsKeyRef((Ref<ACell>) me.getKeyRef())) return false;
		}
		return true;
	}
	
	protected boolean containsAllKeys(MapTree<K, V> map) {
		// fist check this mask contains all of target mask
		if ((this.mask|map.mask)!=this.mask) return false;
		
		for (int i=0; i<FANOUT; i++) {
			Ref<AHashMap<K,V>> child=this.childForDigit(i);
			if (child==null) continue;
			
			Ref<AHashMap<K,V>> mchild=map.childForDigit(i);
			if (mchild==null) continue;
			
			if (!(child.getValue().containsAllKeys(mchild.getValue()))) return false; 
		}
		return true;
	}

	@Override
	public byte getTag() {
		return Tag.MAP;
	}

	@Override
	public AHashMap<K,V> toCanonical() {
		if (count > MapLeaf.MAX_ENTRIES) return this;
		// shouldn't be possible?
		throw new TODOException();
	}

	@Override
	public AHashMap<K, V> slice(long start, long end) {
		if ((start<0)||(end>count)||(end<start)) throw new IndexOutOfBoundsException();
		if (start==end) return Maps.empty();
		if ((start==0) && (end==count)) return this;
		if (end-start<=MapLeaf.MAX_ENTRIES) {
			return smallSlice(start,end);
		}
		
		long pos=0L;
		int cc=children.length;
		short m=0;
		int istart=0;
		int iend=0;
		Ref<AHashMap<K,V>>[] newChildren=children; 
		for (int i=0; i<cc; i++ ) {
			AHashMap<K,V> child=children[i].getValue();
			long csize=child.size();
			long cend=pos+csize;
			if (cend<=start) {
				// haven't reached range yet
				istart++; iend++;
				pos+=csize;
				continue;
			} else if (end<=cend) {
				// we reached the end of the range at this child
				if (i==istart) return child.slice(start-pos,end-pos); // slice within single child! important optimisation
				if (end<=pos) break; //nothing to include from this child, we are done
			} 
			// defensive copy if needed
			if (children==newChildren) newChildren=children.clone();
			AHashMap<K,V> newChild=child.slice(Math.max(0, start-pos), Math.min(csize, end-pos));
			newChildren[i]=newChild.getRef();
			
			// mark mash and and extent index range for new child
			m|=(short)(1<<digitForIndex(i,mask));
			iend++;
			pos+=csize; // advance position
		}
		
		newChildren= Arrays.copyOfRange(newChildren, istart, iend);
		return new MapTree<K,V>(newChildren,shift,m,end-start);
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private AHashMap<K, V> smallSlice(long start, long end) {
		int n=(int)(end-start);
		MapEntry[] items=new MapEntry[n];
		for (int i=0; i<n; i++) {
			items[i]=entryAt(start+i);
		}
		return MapLeaf.unsafeCreate(items);
	}

	@Override
	public boolean isCVMValue() {
		return true;
	}

	// Cache of first hash, we don't want to descend tree repeatedly to find this
	private Hash firstHash;
	
	@Override
	protected Hash getFirstHash() {
		if (firstHash==null) firstHash=children[0].getValue().getFirstHash();
		return firstHash;
	}


}
