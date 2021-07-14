package convex.core.data;

import java.nio.ByteBuffer;

import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.Bits;
import convex.core.util.Utils;

/**
 * Persistent Set for large hash sets requiring tree structure.
 * 
 * Internally implemented as a radix tree, indexed by key hash. Uses an array of
 * child Maps, with a bitmap mask indicating which hex digits are present, i.e.
 * have non-empty children.
 *
 * @param <T>
 */
public class SetTree<T extends ACell> extends AHashSet<T> {
	/**
	 * Child maps, one for each present bit in the mask, max 16
	 */
	private final Ref<AHashSet<T>>[] children;

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

	private SetTree(Ref<AHashSet<T>>[] blocks, int shift, short mask, long count) {
		super(count);
		this.children = blocks;
		this.shift = shift;
		this.mask = mask;
	}

	/**
	 * Computes the total count from an array of Refs to sets. Ignores null Refs in
	 * child array
	 * 
	 * @param children
	 * @return The total count of all child maps
	 */
	private static <T extends ACell> long computeCount(Ref<AHashSet<T>>[] children) {
		long n = 0;
		for (Ref<AHashSet<T>> cref : children) {
			if (cref == null) continue;
			ASet<T> m = cref.getValue();
			n += m.count();
		}
		return n;
	}

	@SuppressWarnings("unchecked")
	public static <V extends ACell> SetTree<V> create(Ref<V>[] newEntries, int shift) {
		int n = newEntries.length;
		if (n <= SetLeaf.MAX_ENTRIES) {
			throw new IllegalArgumentException(
					"Insufficient distinct entries for TreeMap construction: " + newEntries.length);
		}

		// construct full child array
		Ref<AHashSet<V>>[] children = new Ref[16];
		for (int i = 0; i < n; i++) {
			Ref<V> e = newEntries[i];
			int ix = e.getHash().getHexDigit(shift);
			Ref<AHashSet<V>> ref = children[ix];
			if (ref == null) {
				children[ix] = SetLeaf.create(e).getRef();
			} else {
				AHashSet<V> newChild=ref.getValue().includeRef(e,shift+1);
				children[ix] = newChild.getRef();
			}
		}
		return (SetTree<V>) createFull(children, shift);
	}

	/**
	 * Creates a Tree map given child refs for each digit
	 * 
	 * @param children An array of children, may refer to nulls or empty maps which
	 *                 will be filtered out
	 * @return
	 */
	private static <T extends ACell> AHashSet<T> createFull(Ref<AHashSet<T>>[] children, int shift, long count) {
		if (children.length != 16) throw new IllegalArgumentException("16 children required!");
		Ref<AHashSet<T>>[] newChildren = Utils.filterArray(children, a -> {
			if (a == null) return false;
			AHashSet<T> m = a.getValue();
			return ((m != null) && !m.isEmpty());
		});

		if (children != newChildren) {
			return create(newChildren, shift, Utils.computeMask(children, newChildren), count);
		} else {
			return create(children, shift, (short) 0xFFFF, count);
		}
	}

	/**
	 * Create a MapTree with a full compliment of children.
	 * @param <K>
	 * @param <V>
	 * @param newChildren
	 * @param shift Shift for child node
	 * @return
	 */
	private static <T extends ACell> AHashSet<T> createFull(Ref<AHashSet<T>>[] newChildren, int shift) {
		long count=computeCount(newChildren);
		return createFull(newChildren, shift, count);
	}

	/**
	 * Creates a Map with the specified child map Refs. Removes empty maps passed as
	 * children.
	 * 
	 * Returns a ListMap for small maps.
	 * 
	 * @param children Array of Refs to child maps for each bit in mask
	 * @param shift    Shift position (hex digit of key hashes for this map)
	 * @param mask     Mask specifying the hex digits included in the child array at
	 *                 this shift position
	 * @return A new map as specified @
	 */
	@SuppressWarnings("unchecked")
	private static <V extends ACell> AHashSet<V> create(Ref<AHashSet<V>>[] children, int shift, short mask, long count) {
		int cLen = children.length;
		if (Integer.bitCount(mask & 0xFFFF) != cLen) {
			throw new IllegalArgumentException(
					"Invalid child array length " + cLen + " for bit mask " + Utils.toHexString(mask));
		}

		// compress small counts to SetLeaf
		if (count <= SetLeaf.MAX_ENTRIES) {
			Ref<V>[] entries = new Ref[Utils.checkedInt(count)];
			int ix = 0;
			for (Ref<AHashSet<V>> childRef : children) {
				AHashSet<V> child = childRef.getValue();
				long cc = child.count();
				for (long i = 0; i < cc; i++) {
					entries[ix++] = child.getElementRef(i);
				}
			}
			assert (ix == count);
			return SetLeaf.create(entries);
		}
		int sel = (1 << cLen) - 1;
		short newMask = mask;
		for (int i = 0; i < cLen; i++) {
			AHashSet<V> child = children[i].getValue();
			if (child.isEmpty()) {
				newMask = (short) (newMask & ~(1 << digitForIndex(i, mask))); // remove from mask
				sel = sel & ~(1 << i); // remove from selection
			}
		}
		if (mask != newMask) {
			return new SetTree<V>(Utils.filterSmallArray(children, sel), shift, newMask, count);
		}
		return new SetTree<V>(children, shift, mask, count);
	}

	@Override
	public Ref<T> getElementRef(long i) {
		long pos = i;
		for (Ref<AHashSet<T>> c : children) {
			AHashSet<T> child = c.getValue();
			long cc = child.count();
			if (pos < cc) return child.getElementRef(pos);
			pos -= cc;
		}
		throw new IndexOutOfBoundsException("Entry index: " + i);
	}

	@Override
	protected Ref<T> getRefByHash(Hash hash) {
		int digit = Utils.extractDigit(hash, shift);
		int i = Bits.indexForDigit(digit, mask);
		if (i < 0) return null; // not present
		return children[i].getValue().getRefByHash(hash);
	}

	@SuppressWarnings("unchecked")
	@Override
	public AHashSet<T> exclude(ACell key) {
		return excludeRef((Ref<T>) Ref.get(key));
	}

	@Override
	public AHashSet<T> excludeRef(Ref<T> keyRef) {
		int digit = Utils.extractDigit(keyRef.getHash(), shift);
		int i = Bits.indexForDigit(digit, mask);
		if (i < 0) return this; // not present

		// dissoc entry from child
		AHashSet<T> child = children[i].getValue();
		AHashSet<T> newChild = child.excludeRef(keyRef);
		if (child == newChild) return this; // no removal, no change

		AHashSet<T> result=(newChild.isEmpty())?dissocChild(i):replaceChild(i, newChild.getRef());
		return result.toCanonical();
	}
	
	public AHashSet<T> toCanonical() {
		if (count>SetLeaf.MAX_ENTRIES) return this;
		int n=Utils.checkedInt(count);
		@SuppressWarnings("unchecked")
		Ref<T>[] newEntries=new Ref[n];
		for (int i=0; i<n; i++) {
			newEntries[i]=getElementRef(i);
		}
		return new SetLeaf<T>(newEntries);
	}

	@SuppressWarnings("unchecked")
	private AHashSet<T> dissocChild(int i) {
		int bsize = children.length;
		AHashSet<T> child = children[i].getValue();
		Ref<AHashSet<T>>[] newBlocks = (Ref<AHashSet<T>>[]) new Ref<?>[bsize - 1];
		System.arraycopy(children, 0, newBlocks, 0, i);
		System.arraycopy(children, i + 1, newBlocks, i, bsize - i - 1);
		short newMask = (short) (mask & (~(1 << digitForIndex(i, mask))));
		long newCount = count - child.count();
		return create(newBlocks, shift, newMask, newCount);
	}

	@SuppressWarnings("unchecked")
	private SetTree<T> insertChild(int digit, Ref<AHashSet<T>> newChild) {
		int bsize = children.length;
		int i = Bits.positionForDigit(digit, mask);
		short newMask = (short) (mask | (1 << digit));
		if (mask == newMask) throw new Error("Digit already present!");

		Ref<AHashSet<T>>[] newChildren = (Ref<AHashSet<T>>[]) new Ref<?>[bsize + 1];
		System.arraycopy(children, 0, newChildren, 0, i);
		System.arraycopy(children, i, newChildren, i + 1, bsize - i);
		newChildren[i] = newChild;
		long newCount = count + newChild.getValue().count();
		return (SetTree<T>) create(newChildren, shift, newMask, newCount);
	}

	/**
	 * Replaces the child ref at a given index position. Will return this if no change
	 * 
	 * @param i
	 * @param newChild
	 * @return @
	 */
	private AHashSet<T> replaceChild(int i, Ref<AHashSet<T>> newChild) {
		if (children[i] == newChild) return this;
		AHashSet<T> oldChild = children[i].getValue();
		Ref<AHashSet<T>>[] newChildren = children.clone();
		newChildren[i] = newChild;
		long newCount = count + newChild.getValue().count() - oldChild.count();
		return create(newChildren, shift, mask, newCount);
	}

	public static int digitForIndex(int index, short mask) {
		// scan mask for specified index
		int found = 0;
		for (int i = 0; i < 16; i++) {
			if ((mask & (1 << i)) != 0) {
				if (found++ == index) return i;
			}
		}
		throw new IllegalArgumentException("Index " + index + " not available in mask map: " + Utils.toHexString(mask));
	}

	@SuppressWarnings("unchecked")
	@Override
	public SetTree<T> include(ACell value) {
		Ref<T> keyRef = (Ref<T>) Ref.get(value);
		return includeRef(keyRef, shift);
	}

	@Override
	protected SetTree<T> includeRef(Ref<T> e, int shift) {
		if (this.shift != shift) {
			throw new Error("Invalid shift!");
		}
		Ref<T> keyRef = e;
		int digit = Utils.extractDigit(keyRef.getHash(), shift);
		int i = Bits.indexForDigit(digit, mask);
		if (i < 0) {
			// location not present
			AHashSet<T> newChild = SetLeaf.create(e);
			return insertChild(digit, newChild.getRef());
		} else {
			// location needs update
			AHashSet<T> child = children[i].getValue();
			AHashSet<T> newChild = child.includeRef(e, shift + 1);
			if (child == newChild) return this;
			return (SetTree<T>) replaceChild(i, newChild.getRef());
		}
	}
	
	@Override
	public AHashSet<T> includeRef(Ref<T> ref) {
		return includeRef(ref,shift);
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.SET;
		return encodeRaw(bs,pos);
	}
	
	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos = Format.writeVLCLong(bs,pos, count);
		
		bs[pos++] = (byte) shift;
		pos = Utils.writeShort(bs, pos,mask);

		int ilength = children.length;
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
	
	public static int MAX_ENCODING_LENGTH = 4 + Format.MAX_EMBEDDED_LENGTH * 16;

	/**
	 * Reads a ListMap from the provided ByteBuffer Assumes the header byte and count is
	 * already read.
	 * 
	 * @param bb
	 * @param count
	 * @return TreeMap instance as read from ByteBuffer
	 * @throws BadFormatException
	 */
	@SuppressWarnings("unchecked")
	public static <V extends ACell> SetTree<V> read(ByteBuffer bb, long count) throws BadFormatException {
		int shift = bb.get();
		short mask = bb.getShort();

		int ilength = Integer.bitCount(mask & 0xFFFF);
		Ref<AHashSet<V>>[] blocks = (Ref<AHashSet<V>>[]) new Ref<?>[ilength];

		for (int i = 0; i < ilength; i++) {
			// need to read as a Ref
			Ref<AHashSet<V>> ref = Format.readRef(bb);
			blocks[i] = ref;
		}
		// create directly, we have all values
		SetTree<V> result = new SetTree<V>(blocks, shift, mask, count);
		if (!result.isValidStructure()) throw new BadFormatException("Problem with TreeMap invariants");
		return result;
	}

	@Override
	public boolean isCanonical() {
		if (count <= MapLeaf.MAX_ENTRIES) return false;
		return true;
	}
	
	@Override public final boolean isCVMValue() {
		return shift==0;
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
	public SetTree<T> updateRefs(IRefFunction func) {
		int n = children.length;
		if (n == 0) return this;
		Ref<AHashSet<T>>[] newChildren = children;
		for (int i = 0; i < n; i++) {
			Ref<AHashSet<T>> child = children[i];
			Ref<AHashSet<T>> newChild = (Ref<AHashSet<T>>) func.apply(child);
			if (child != newChild) {
				if (children == newChildren) {
					newChildren = children.clone();
				}
				newChildren[i] = newChild;
			}
		}
		if (newChildren == children) return this;
		// Note: we assume no key hashes have changed, so structure is the same
		return new SetTree<>(newChildren, shift, mask, count);
	}

	@Override
	public AHashSet<T> mergeWith(AHashSet<T> b, int setOp) {
		return mergeWith(b, setOp, this.shift);
	}

	@Override
	protected AHashSet<T> mergeWith(AHashSet<T> b, int setOp, int shift) {
		if ((b instanceof SetTree)) {
			SetTree<T> bt = (SetTree<T>) b;
			if (this.shift != bt.shift) throw new Error("Misaligned shifts!");
			return mergeWith(bt, setOp, shift);
		}
		if ((b instanceof SetLeaf)) return mergeWith((SetLeaf<T>) b, setOp, shift);
		throw new Error("Unrecognised map type: " + b.getClass());
	}

	@SuppressWarnings("unchecked")
	private AHashSet<T> mergeWith(SetTree<T> b, int setOp, int shift) {
		// assume two TreeMaps with identical prefix and shift
		assert (b.shift == shift);
		int fullMask = mask | b.mask;
		// We are going to build full child list only if needed
		Ref<AHashSet<T>>[] newChildren = null;
		for (int digit = 0; digit < 16; digit++) {
			int bitMask = 1 << digit;
			if ((fullMask & bitMask) == 0) continue; // nothing to merge at this index
			AHashSet<T> ac = childForDigit(digit).getValue();
			AHashSet<T> bc = b.childForDigit(digit).getValue();
			AHashSet<T> rc = ac.mergeWith(bc, setOp, shift + 1);
			if (ac != rc) {
				if (newChildren == null) {
					newChildren = (Ref<AHashSet<T>>[]) new Ref<?>[16];
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
	private AHashSet<T> mergeWith(SetLeaf<T> b, int setOp, int shift) {
		Ref<AHashSet<T>>[] newChildren = null;
		int ix = 0;
		for (int i = 0; i < 16; i++) {
			int imask = (1 << i); // mask for this digit
			if ((mask & imask) == 0) continue;
			Ref<AHashSet<T>> cref = children[ix++];
			AHashSet<T> child = cref.getValue();
			SetLeaf<T> bSubset = b.filterHexDigits(shift, imask); // filter only relevant elements in b
			AHashSet<T> newChild = child.mergeWith(bSubset, setOp, shift + 1);
			if (child != newChild) {
				if (newChildren == null) {
					newChildren = (Ref<AHashSet<T>>[]) new Ref<?>[16];
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
		AHashSet<T> result = (newChildren == null) ? this : createFull(newChildren, shift);

		SetLeaf<T> extras = b.filterHexDigits(shift, ~mask);
		int en = extras.size();
		for (int i = 0; i < en; i++) {
			Ref<T> e = extras.getRef(i);
			Ref<T> newE = applyOp(setOp,null,e);
			if (newE != null) {
				// include only new keys where function result is not null. Re-use existing
				// entry if possible.
				result = result.includeRef(newE, shift);
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
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private Ref<AHashSet<T>> childForDigit(int digit) {
		int ix = Bits.indexForDigit(digit, mask);
		if (ix < 0) return (Ref)Sets.emptyRef();
		return children[ix];
	}

	@Override
	public boolean equals(ASet<T> a) {
		if (!(a instanceof SetTree)) return false;
		return equals((SetTree<T>) a);
	}

	boolean equals(SetTree<T> b) {
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
	public void validate() throws InvalidDataException {
		super.validate();
		// Perform validation for this tree position
		validateWithPrefix("");
	}

	@Override
	protected void validateWithPrefix(String prefix) throws InvalidDataException {
		if (mask == 0) throw new InvalidDataException("TreeMap must have children!", this);
		if (shift != prefix.length()) {
			throw new InvalidDataException("Invalid prefix [" + prefix + "] for TreeMap with shift=" + shift, this);
		}
		int bsize = children.length;

		long childCount=0;;
		for (int i = 0; i < bsize; i++) {
			if (children[i] == null)
				throw new InvalidDataException("Null child ref at " + prefix + Utils.toHexChar(digitForIndex(i, mask)),
						this);
			ACell o = children[i].getValue();
			if (!(o instanceof AHashMap)) {
				throw new InvalidDataException(
						"Expected map child at " + prefix + Utils.toHexChar(digitForIndex(i, mask)), this);
			}
			@SuppressWarnings("unchecked")
			AHashSet<T> child = (AHashSet<T>) o;
			if (child.isEmpty())
				throw new InvalidDataException("Empty child at " + prefix + Utils.toHexChar(digitForIndex(i, mask)),
						this);
			int d = digitForIndex(i, mask);
			child.validateWithPrefix(prefix + Utils.toHexChar(d));
			
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

	@Override
	public boolean containsAll(ASet<T> b) {
		if (b instanceof SetTree) {
			return containsAll((SetTree<T>)b);
		}
		// must be a SetLeaf
		long n=b.count;
		for (long i=0; i<n; i++) {
			Ref<T> me=b.getElementRef(i);
			if (!this.containsHash(me.getHash())) return false;
		}
		return true;
	}
	
	protected boolean containsAll(SetTree<T> map) {
		// fist check this mask contains all of target mask
		if ((this.mask|map.mask)!=this.mask) return false;
		
		for (int i=0; i<16; i++) {
			Ref<AHashSet<T>> child=this.childForDigit(i);
			if (child==null) continue;
			
			Ref<AHashSet<T>> mchild=map.childForDigit(i);
			if (mchild==null) continue;
			
			if (!(child.getValue().containsAll(mchild.getValue()))) return false; 
		}
		return true;
	}

	@Override
	public Ref<T> getValueRef(ACell k) {
		return getRefByHash(Hash.compute(k));
	}

	@Override
	protected <R> void copyToArray(R[] arr, int offset) {
		for (int i=0; i<children.length; i++) {
			AHashSet<T> child=children[i].getValue();
			child.copyToArray(arr,offset);
			offset=Utils.checkedInt(offset+child.count());
		}
	}

	@Override
	public boolean containsHash(Hash hash) {
		return getRefByHash(hash)!=null;
	}
}
