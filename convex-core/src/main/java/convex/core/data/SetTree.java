package convex.core.data;

import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.Panic;
import convex.core.util.Bits;
import convex.core.util.Utils;

/**
 * Persistent Set for large hash sets requiring tree structure.
 * 
 * Internally implemented as a radix tree, indexed by key hash. Uses an array of
 * child Maps, with a bitmap mask indicating which hex digits are present, i.e.
 * have non-empty children.
 *
 * @param <T> Type of Set elements
 */
public class SetTree<T extends ACell> extends AHashSet<T> {

	/**
	 * Child maps, one for each present bit in the mask, max 16
	 */
	private final Ref<AHashSet<T>>[] children;

	/**
	 * Shift position of this @link SetTree node in number of hex digits. 0 at top level, 1 at next level up etc
	 */
	final int shift;

	/**
	 * Mask indicating which hex digits are present in the child array e.g. 0x0001
	 * indicates all children are in the '0' digit. e.g. 0xFFFF indicates there are
	 * children for every digit.
	 */
	final short mask;

	private SetTree(Ref<AHashSet<T>>[] children, int shift, short mask, long count) {
		super(count);
		this.children = children;
		this.shift = shift;
		this.mask = mask;
	}
	
	public static <T extends ACell> SetTree<T> unsafeCreate(Ref<AHashSet<T>>[] children,int shift, short mask, long count) {
		return new SetTree<T>(children,shift,mask,count);
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

	/**
	 * Create a SetTree given a number of element Refs to distribute among children.
	 * O(n) in number of elements.
	 * 
	 * @param <V> Type of elements
	 * @param elementRefs Array of Refs to elements
	 * @param shift Hex digit position at which to split children.
	 * @return New SetTree node
	 */
	@SuppressWarnings("unchecked")
	public static <V extends ACell> SetTree<V> create(Ref<V>[] elementRefs, int shift) {
		int n = elementRefs.length;
		if (n <= SetLeaf.MAX_ELEMENTS) {
			throw new IllegalArgumentException(
					"Insufficient distinct entries for TreeMap construction: " + elementRefs.length);
		}

		// construct full child array
		Ref<AHashSet<V>>[] children = new Ref[16];
		for (int i = 0; i < n; i++) {
			Ref<V> e = elementRefs[i];
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
	 * Creates a SetTree given child Refs for each digit
	 * 
	 * @param children An array of children, may be null or refer to empty Sets which
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
	 * Create a SetTree with a full compliment of 16 children.
	 * @param <T> Type of Set elements
	 * @param newChildren
	 * @param shift Shift for child node
	 * @return
	 */
	private static <T extends ACell> AHashSet<T> createFull(Ref<AHashSet<T>>[] newChildren, int shift) {
		long count=computeCount(newChildren);
		return createFull(newChildren, shift, count);
	}

	/**
	 * Creates a Set using specified child Set Refs. Removes empty Sets passed as
	 * children.
	 * 
	 * Returns a SetLeaf for small Sets.
	 * 
	 * @param children Array of Refs to child sets for each bit in mask
	 * @param shift    Shift position (hex digit in hashes for this node)
	 * @param mask     Mask specifying the hex digits included in the child array at
	 *                 this shift position
	 * @return A new set as required
	 */
	@SuppressWarnings("unchecked")
	private static <V extends ACell> AHashSet<V> create(Ref<AHashSet<V>>[] children, int shift, short mask, long count) {
		int cLen = children.length;
		if (Integer.bitCount(mask & 0xFFFF) != cLen) {
			throw new IllegalArgumentException(
					"Invalid child array length " + cLen + " for bit mask " + Utils.toHexString(mask));
		}

		// compress small counts to SetLeaf
		if (count <= SetLeaf.MAX_ELEMENTS) {
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
		int digit = hash.getHexDigit(shift);
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
	public AHashSet<T> excludeRef(Ref<?> keyRef) {
		int digit = keyRef.getHash().getHexDigit(shift);
		int i = Bits.indexForDigit(digit, mask);
		if (i < 0) return this; // not present

		// dissoc entry from child
		AHashSet<T> child = children[i].getValue();
		AHashSet<T> newChild = child.excludeRef(keyRef);
		if (child == newChild) return this; // no removal, no change

		AHashSet<T> result=(newChild.isEmpty())?dissocChild(i):replaceChild(i, newChild.getRef());
		return result.toCanonical();
	}
	
	@Override
	public boolean isCanonical() {
		// We are canonical if and only if elements would not fit in a SetLeaf
		return (count > SetLeaf.MAX_ELEMENTS);
	}
	
	@Override
	public AHashSet<T> toCanonical() {
		if (isCanonical()) return this;
		int n=(int)count; // safe since we know n is in range 0..16
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
		if (mask == newMask) throw new Panic("Digit already present!");

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
	 * @return Updated SetTree
	 */
	protected AHashSet<T> replaceChild(int i, Ref<AHashSet<T>> newChild) {
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
		int digit = keyRef.getHash().getHexDigit(shift);
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
	 * Reads a SetTree from the provided Blob encoding
	 * 
	 * @param b Blob to read from
	 * @param pos Start position in Blob (location of tag byte)
	 * @param count Number of elements	 
	 * @return New decoded instance
	 * @throws BadFormatException In the event of any encoding error
	 */
	public static <V extends ACell> SetTree<V> read(Blob b, int pos, long count) throws BadFormatException {
		int headerLen=1+Format.getVLCLength(count);
		int epos=pos+headerLen;
		
		int shift=b.byteAt(epos++);
		short mask=b.shortAt(epos);
		epos+=2;
		
		int ilength = Integer.bitCount(mask & 0xFFFF);
		
		@SuppressWarnings("unchecked")
		Ref<AHashSet<V>>[] blocks = (Ref<AHashSet<V>>[]) new Ref<?>[ilength];
		for (int i = 0; i < ilength; i++) {
			// need to read as a Ref
			Ref<AHashSet<V>> ref = Format.readRef(b,epos);
			epos+=ref.getEncodingLength();
			blocks[i] = ref;
		}
		
		SetTree<V> result = new SetTree<V>(blocks, shift, mask, count);
		if (!result.isValidStructure()) throw new BadFormatException("Problem with TreeMap invariants");
		Blob enc=b.slice(pos,epos);
		result.attachEncoding(enc);
		return result;
	}


	
	@Override public final boolean isCVMValue() {
		return shift==0;
	}

	@Override
	public int getRefCount() {
		return children.length;
	}
	
	/**
	 * Returns the mask value of this SetTree node. Each set bit indicates the presence of a child set 
	 * with the given hex digit
	 * @return Mask value
	 */
	public short getMask() {
		return mask;
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
			if (this.shift != bt.shift) throw new Panic("Misaligned shifts!");
			return mergeWith(bt, setOp, shift);
		}
		if ((b instanceof SetLeaf)) return mergeWith((SetLeaf<T>) b, setOp, shift);
		throw new Panic("Unrecognised map type: " + b.getClass());
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

	@SuppressWarnings("unchecked")
	@Override
	public boolean equals(ACell a) {
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

		validateWithPrefix(Hash.EMPTY_HASH,0,-1);
	}
	
	@Override
	protected void validateWithPrefix(Hash base, int digit, int position) throws InvalidDataException {
		if (mask == 0) throw new InvalidDataException("TreeMap must have children!", this);
		if ((shift <0)||(shift>MAX_SHIFT)) {
			throw new InvalidDataException("Invalid shift for SetTree", this);
		}
		
		if (count<=SetLeaf.MAX_ELEMENTS) {
			throw new InvalidDataException("Count too small [" + count + "] for SetTree", this);
		}

		Hash firstHash;
		try {
			firstHash=getElementRef(0).getHash();
		} catch (ClassCastException e) {
			throw new InvalidDataException("Bad child type:" +e.getMessage(), this);
		}
		
		int bsize = children.length;

		long childCount=0;;
		for (int i = 0; i < bsize; i++) {
			if (children[i] == null) {
				throw new InvalidDataException("Null child ref at index " + i,this);
			}
			
			ACell o = children[i].getValue();
			if (!(o instanceof AHashSet)) {
				throw new InvalidDataException(
						"Expected AHashSet child at index " + i +" but got "+Utils.getClassName(o), this);
			}
			@SuppressWarnings("unchecked")
			AHashSet<T> child = (AHashSet<T>) o;
			if (child.isEmpty())
				throw new InvalidDataException("Empty child at index " + i,this);
			
			if (child instanceof SetTree) {
				SetTree<T> childTree=(SetTree<T>) child;
				int expectedShift=shift+1;
				if (childTree.shift!=expectedShift) {
					throw new InvalidDataException("Wrong child shift ["+childTree.shift+"], expected ["+expectedShift+"]",this);
				}
			}
			
			Hash childHash=child.getElementRef(0).getHash();
			long pmatch=firstHash.hexMatch(childHash);
			if (pmatch<shift) throw new InvalidDataException("Mismatched child hash [" + childHash +"] with this ["+firstHash+"]",
					this);
			
			int d = digitForIndex(i, mask);
			child.validateWithPrefix(firstHash ,d,position+1);
			
			childCount += child.count();
		}
		
		if (count != childCount) {
			throw new InvalidDataException("Bad child count, expected " + count + " but children had: " + childCount, this);
		}
	}

	private boolean isValidStructure() {
		if (count <= SetLeaf.MAX_ELEMENTS) return false;
		if (children.length != Integer.bitCount(mask & 0xFFFF)) return false;
		for (int i = 0; i < children.length; i++) {
			Ref<AHashSet<T>> child = children[i];
			if (child == null) return false;
		}
		return true;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (!isValidStructure()) throw new InvalidDataException("Bad structure", this);
	}

	@Override
	public boolean containsAll(ASet<?> b) {
		if (b instanceof SetTree) {
			return containsAll((SetTree<?>)b);
		}
		// must be a SetLeaf
		long n=b.count;
		for (long i=0; i<n; i++) {
			Ref<?> me=b.getElementRef(i);
			if (!this.containsHash(me.getHash())) return false;
		}
		return true;
	}
	
	protected boolean containsAll(SetTree<?> other) {
		// fist check this mask contains all of target mask
		if ((this.mask|other.mask)!=this.mask) return false;
		
		for (int i=0; i<16; i++) {
			Ref<AHashSet<T>> child=this.childForDigit(i);
			if (child==null) continue;
			
			Ref<?> mchild = other.childForDigit(i);
			if (mchild==null) continue;
			
			if (!(child.getValue().containsAll((ASet<?>) mchild.getValue()))) return false; 
		}
		return true;
	}

	@Override
	public Ref<T> getValueRef(ACell k) {
		Hash h=Hash.get(k);
		return getRefByHash(h);
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

	@SuppressWarnings("unchecked")
	@Override
	public ASet<T> slice(long start, long end) {
		if (start<0) return null;
		if (end>count) return null;
		long n=end-start;
		if (n<0) return null;
		if (n==count) return this;
		if (n==0) return empty();

		if (n<=SetLeaf.MAX_ELEMENTS) {
			int nc=(int)n;
			Ref<T>[] elems=new Ref[nc];
			for (int i=0; i<nc; i++) {
				elems[i]=Ref.get(get(start+i));
			}
			return SetLeaf.create(elems);
		}
		
		SetTree<T> result=this;
		int nc=children.length;
		long cstart=0;
		for (int i=0; i<nc; i++) {
			AHashSet<T> c=result.children[i].getValue();
			long cc=c.count();
			long cend=cstart+cc;
			if ((cend<=start)||(cstart>=end)) {
				// Remove entire child
				result=(SetTree<T>) result.dissocChild(i);
				i--;
				nc--;
			} else {
				long istart=Math.max(0, start-cstart);
				long iend=Math.min(cc, end-cstart);
				AHashSet<T> nchild=(AHashSet<T>) c.slice(istart,iend);
				if (nchild!=c) {
					result=(SetTree<T>) result.replaceChild(i, nchild.getRef());
				}
			}
			cstart=cend;
		}
		return result;
	}

}
