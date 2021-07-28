package convex.core.data;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Predicate;

import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;
import convex.core.util.Bits;
import convex.core.util.Errors;
import convex.core.util.Utils;

/**
 * BlobMap node implementation supporting: 
 * 
 * <ul>
 * <li>An optional prefix string</li>
 * <li>An optional entry with this prefix </li>
 * <li>Up to 16 child entries at the next level of depth</li>
 * </ul>
 * @param <K> Type of Keys
 * @param <V> Type of values
 */
public class BlobMap<K extends ABlob, V extends ACell> extends ABlobMap<K, V> {
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static final Ref<BlobMap>[] EMPTY_CHILDREN = new Ref[0];

	/**
	 * Empty BlobMap singleton
	 */
	public static final BlobMap<ABlob, ACell> EMPTY = new BlobMap<ABlob, ACell>(0, 0, null, EMPTY_CHILDREN,
			(short) 0, 0L);
	
	static {
		// Set empty Ref flags as internal embedded constant
		EMPTY.getRef().setFlags(Ref.INTERNAL_FLAGS);
	}

	/**
	 * Child entries, i.e. nodes with keys where this node is a common prefix. Only contains children where mask is set.
	 * Child entries must have at least one entry.
	 */
	private final Ref<BlobMap<K, V>>[] children;

	/**
	 * Entry for this node of the radix tree. Invariant assumption that the prefix
	 * is correct. May be null if there is no entry at this node.
	 */
	private final MapEntry<K, V> entry;

	/**
	 * Mask of child entries, 16 bits for each hex digit that may be present.
	 */
	private final short mask;

	/**
	 * Depth of radix tree in number of hex digits. Top level is 0. 
	 * Children should have depth = parent depth + parent prefixLength + 1
	 */
	private final long depth;

	/**
	 * Length of prefix, where the tree branches beyond depth. 0 = no prefix.
	 */
	private final long prefixLength;

	@SuppressWarnings({ "rawtypes", "unchecked" })
	protected BlobMap(long depth, long prefixLength, MapEntry<K, V> entry, Ref<BlobMap>[] entries,
			short mask, long count) {
		super(count);
		this.depth = depth;
		this.prefixLength = prefixLength;
		this.entry = entry;
		int cn = Utils.bitCount(mask);
		if (cn != entries.length) throw new IllegalArgumentException(
				"Illegal mask: " + Utils.toHexString(mask) + " for given number of children: " + cn);
		this.children = (Ref[]) entries;
		this.mask = mask;
	}

	public static <K extends ABlob, V extends ACell> BlobMap<K, V> create(MapEntry<K, V> me) {
		ACell k=me.getKey();
		if (!(k instanceof ABlob)) return null;
		long hexLength = ((ABlob)k).hexLength();
		return new BlobMap<K, V>(0, hexLength, me, EMPTY_CHILDREN, (short) 0, 1L);
	}

	private static <K extends ABlob, V extends ACell> BlobMap<K, V> createAtDepth(MapEntry<K, V> me, long depth) {
		Blob prefix = me.getKey().toBlob();
		long hexLength = prefix.hexLength();
		if (depth > hexLength)
			throw new IllegalArgumentException("Depth " + depth + " too deep for key with hexLength: " + hexLength);
		return new BlobMap<K, V>(depth, hexLength - depth, me, EMPTY_CHILDREN, (short) 0, 1L);
	}

	public static <K extends ABlob, V extends ACell> BlobMap<K, V> create(K k, V v) {
		MapEntry<K, V> me = MapEntry.create(k, v);
		long hexLength = k.hexLength();
		return new BlobMap<K, V>(0, hexLength, me, EMPTY_CHILDREN, (short) 0, 1L);
	}
	
	public static <K extends ABlob, V extends ACell> BlobMap<K, V> of(Object k, Object v) {
		return create(RT.cvm(k),RT.cvm(v));
	}

	@Override
	public boolean isCanonical() {
		return true;
	}
	
	@Override public final boolean isCVMValue() {
		return (depth==0);
	}

	@SuppressWarnings("unchecked")
	@Override
	public BlobMap<K,V> updateRefs(IRefFunction func) {
		MapEntry<K, V> newEntry = (entry == null) ? null : entry.updateRefs(func);
		Ref<BlobMap<K, V>>[] newChildren = Ref.updateRefs(children, func);
		if ((entry == newEntry) && (children == newChildren)) return this;
		return new BlobMap<K, V>(depth, prefixLength, newEntry, (Ref[])newChildren, mask, count);
	}

	@Override
	public boolean containsValue(Object value) {
		if ((entry != null) && Utils.equals(entry.getValue(), value)) return true;
		for (int i = 0; i < count; i++) {
			if (children[i].getValue().containsValue(value)) return true;
		}
		return false;
	}

	@Override
	public V get(ABlob key) {
		MapEntry<K, V> me = getEntry(key);
		if (me == null) return null;
		return me.getValue();
	}

	@Override
	public MapEntry<K, V> getEntry(ABlob key) {
		long kl = key.hexLength();
		long pl = depth + prefixLength;
		if (kl < pl) return null; // key is too short to start with current prefix

		if (kl == pl) {
			if ((entry!=null)&&(key.equalsBytes(entry.getKey()))) return entry; // we matched this key exactly!
			return null; // entry does not exist
		}

		int digit = key.getHexDigit(pl);
		BlobMap<K, V> cc = getChild(digit);

		if (cc == null) return null;
		return cc.getEntry(key);
	}

	/**
	 * Gets the child for a specific digit, or null if not found
	 * 
	 * @param digit
	 * @return
	 */
	private BlobMap<K, V> getChild(int digit) {
		int i = Bits.indexForDigit(digit, mask);
		if (i < 0) return null;
		return (BlobMap<K, V>) children[i].getValue();
	}

	@Override
	public int getRefCount() {
		return ((entry == null) ? 0 : entry.getRefCount()) + children.length;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <R extends ACell> Ref<R> getRef(int i) {
		if (entry != null) {
			int erc = entry.getRefCount();
			if (i < erc) return entry.getRef(i);
			i -= erc;
		}
		int cl = children.length;
		if (i < cl) return (Ref<R>) children[i];
		throw new IndexOutOfBoundsException("No ref for index:" + i);
	}
	
	@SuppressWarnings("unchecked")
	public BlobMap<K, V> assoc(ACell key, ACell value) {
		if (!(key instanceof ABlob)) return null;
		return assocEntry(MapEntry.create((K)key, (V)value));
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Override
	public BlobMap<K, V> dissoc(ABlob k) {
		if (count <= 1) {
			if (count == 0) return this; // Must already be empty singleton
			if (entry.getKey().equalsBytes(k)) {
				return (depth==0)?empty():null;
			}
			return this; // leave existing entry in place
		}
		long pDepth = depth + prefixLength; // hex depth of this node including prefix
		long kl = k.hexLength(); // hex length of key to dissoc
		if (kl < pDepth) {
			// no match for sure, so no change
			return this;
		}
		if (kl == pDepth) {
			// need to check for match with current entry
			if (entry == null) return this;
			if (!k.equalsBytes(entry.getKey())) return this;
			// at this point have matched entry exactly. So need to remove it safely while
			// preserving invariants
			if (children.length == 1) {
				// need to promote child to the current depth
				BlobMap<K, V> c = (BlobMap<K, V>) children[0].getValue();
				return new BlobMap(depth, (c.depth + c.prefixLength) - depth, c.entry, c.children, c.mask,
						count - 1);
			} else {
				// Clearing current entry, keeping existing children (must be 2+)
				return new BlobMap(depth, prefixLength, null, children, mask, count - 1);
			}
		}
		// dissoc beyond current prefix length, so need to check children
		int digit = k.getHexDigit(pDepth);
		int childIndex = Bits.indexForDigit(digit, mask);
		if (childIndex < 0) return this; // key miss
		// we know we need to replace a child
		BlobMap<K, V> oldChild = (BlobMap<K, V>) children[childIndex].getValue();
		BlobMap<K, V> newChild = oldChild.dissoc(k);
		BlobMap<K,V> r=this.withChild(digit, oldChild, newChild);
		
		// check if whole blobmap was emptied
		if ((r==null)&&(depth==0)) r= empty();
		return r;
	}

	/**
	 * Prefix blob, must contain hex digits in the range [depth,depth+prefixLength).
	 * 
	 * May contain more hex digits in memory, this is irrelevant from the
	 * perspective of serialisation.
	 * 
	 * Typically we populate with the key of the first entry added to avoid
	 * unnecessary blob instances being created.
	 */
	private ABlob getPrefix() {
		if (entry!=null) return entry.getKey();
		int n=children.length;
		if (n==0) return Blob.EMPTY;
		return children[0].getValue().getPrefix();
	}

	@Override
	protected void accumulateEntrySet(HashSet<Entry<K, V>> h) {
		for (int i = 0; i < children.length; i++) {
			children[i].getValue().accumulateEntrySet(h);
		}
		if (entry != null) h.add(entry);
	}

	@Override
	protected void accumulateKeySet(HashSet<K> h) {
		for (int i = 0; i < children.length; i++) {
			children[i].getValue().accumulateKeySet(h);
		}
		if (entry != null) h.add(entry.getKey());
	}

	@Override
	protected void accumulateValues(ArrayList<V> al) {
		// add this entry first, since we want lexicographic order
		if (entry != null) al.add(entry.getValue());
		for (int i = 0; i < children.length; i++) {
			children[i].getValue().accumulateValues(al);
		}
	}

	@Override
	public void forEach(BiConsumer<? super K, ? super V> action) {
		if (entry != null) action.accept(entry.getKey(), entry.getValue());
		for (int i = 0; i < children.length; i++) {
			children[i].getValue().forEach(action);
		}
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Override
	public BlobMap<K, V> assocEntry(MapEntry<K, V> e) {
		if (count == 0L) return create(e);
		if (count == 1L) {
			assert (mask == (short) 0); // should be no children
			if (entry.keyEquals(e)) {
				if (entry == e) return this;
				// recreate, preserving current depth
				return createAtDepth(e, depth);
			}
		}
		ABlob k = e.getKey();
		long pDepth = this.prefixDepth(); // hex depth of this node including prefix
		long newKeyLength = k.hexLength(); // hex length of new key
		long mkl; // matched key length
		ABlob prefix=getPrefix();
		if (newKeyLength >= pDepth) {
			// constrain relevant key length by match with current prefix
			mkl = depth + k.hexMatchLength(prefix, depth, prefixLength);
		} else {
			mkl = depth + k.hexMatchLength(prefix, depth, newKeyLength - depth);
		}
		if (mkl < pDepth) {
			// we collide at a point shorter than the current prefix length
			if (mkl == newKeyLength) {
				// new key is subset of the current prefix, so split prefix at key position mkl
				// doesn't need to adjust child depths, since they are splitting at the same
				// point
				long newDepth=mkl+1; // depth for new child
				BlobMap<K, V> split = new BlobMap<K, V>(newDepth, pDepth - newDepth, entry, (Ref[]) children, mask,
						count);
				int splitDigit = prefix.getHexDigit(mkl);
				short splitMask = (short) (1 << splitDigit);
				BlobMap<K, V> result = new BlobMap<K, V>(depth, mkl - depth, e, new Ref[] { split.getRef() },
						splitMask, count + 1);
				return result;
			} else {
				// we need to fork the current prefix in two at position mkl
				long newDepth=mkl+1; // depth for new children
				
				BlobMap<K, V> branch1 = new BlobMap<K, V>(newDepth, pDepth - newDepth, entry, (Ref[]) children, mask,
						count);
				BlobMap<K, V> branch2 = new BlobMap<K, V>(newDepth, newKeyLength - newDepth, e, (Ref[]) EMPTY_CHILDREN,
						(short) 0, 1L);
				int d1 = prefix.getHexDigit(mkl);
				int d2 = k.getHexDigit(mkl);
				if (d1 > d2) {
					// swap to get in right order
					BlobMap<K, V> temp = branch1;
					branch1 = branch2;
					branch2 = temp;
				}
				Ref[] newChildren = new Ref[] { branch1.getRef(), branch2.getRef() };
				short newMask = (short) ((1 << d1) | (1 << d2));
				BlobMap<K, V> fork = new BlobMap<K, V>(depth, mkl - depth, null, newChildren, newMask, count + 1L);
				return fork;
			}
		}
		assert (newKeyLength >= pDepth);
		if (newKeyLength == pDepth) {
			// we must have matched the current entry exactly
			if (entry == null) {
				// just add entry at this position
				return new BlobMap<K, V>(depth, prefixLength, e, (Ref[]) children, mask, count + 1);
			}
			if (entry == e) return this;

			// swap entry, no need to change count
			return new BlobMap<K, V>(depth, prefixLength, e, (Ref[]) children, mask, count);
		}
		// at this point we have matched full prefix, but new key length is longer.
		// so we need to update (or add) exactly one child
		int childDigit = k.getHexDigit(pDepth);
		BlobMap<K, V> oldChild = getChild(childDigit);
		BlobMap<K, V> newChild;
		if (oldChild == null) {
			newChild = createAtDepth(e, pDepth+1); // Myst be at least 1 beyond current prefix
		} else {
			newChild = oldChild.assocEntry(e);
		}
		return withChild(childDigit, oldChild, newChild); // can't be null since associng
	}

	/**
	 * Updates this BlobMap with a new child.
	 * 
	 * Either oldChild or newChild may be null. Empty maps are treated as null.
	 * 
	 * @param childDigit Digit for new child
	 * @param newChild
	 * @return BlobMap with child removed, or null if BlobMap was deleted entirely
	 */
	@SuppressWarnings({ "rawtypes", "unchecked"})
	private BlobMap<K, V> withChild(int childDigit, BlobMap<K, V> oldChild, BlobMap<K, V> newChild) {
		// consider empty children as null
		//if (oldChild == EMPTY) oldChild = null;
		//if (newChild == EMPTY) newChild = null;
		if (oldChild == newChild) return this;

		int n = children.length;
		// we need a new child array
		Ref[] newChildren = children;
		if (oldChild == null) {
			// definitely need a new entry
			newChildren = new Ref[n + 1];
			int newPos = Bits.positionForDigit(childDigit, mask);
			short newMask = (short) (mask | (1 << childDigit));

			System.arraycopy(children, 0, newChildren, 0, newPos); // earlier entries
			newChildren[newPos] = newChild.getRef();
			System.arraycopy(children, newPos, newChildren, newPos + 1, n - newPos); // later entries
			return new BlobMap<K, V>(depth, prefixLength, entry, newChildren, newMask,
					count + newChild.count());
		} else {
			// dealing with an existing child
			if (newChild == null) {
				// need to delete an existing child
				int delPos = Bits.positionForDigit(childDigit, mask);

				// handle special case where we need to promote the remaining child
				if (entry == null) {
					if (n == 2) {
						BlobMap<K, V> rm = (BlobMap<K, V>) children[1 - delPos].getValue();
						long newPLength = prefixLength + rm.prefixLength+1;
						return new BlobMap<K, V>(depth, newPLength, rm.entry, (Ref[]) rm.children, rm.mask,
								rm.count());
					} else if (n == 1) {
						// deleting entire BlobMap!
						return null;
					}
				}
				if (n==0) {
					System.out.print("BlobMap Bad!");
				}
				newChildren = new Ref[n - 1];
				short newMask = (short) (mask & ~(1 << childDigit));
				System.arraycopy(children, 0, newChildren, 0, delPos); // earlier entries
				System.arraycopy(children, delPos + 1, newChildren, delPos, n - delPos - 1); // later entries
				return new BlobMap<K, V>(depth, prefixLength, entry, newChildren, newMask,
						count - oldChild.count());
			} else {
				// need to replace a child
				int childPos = Bits.positionForDigit(childDigit, mask);
				newChildren = children.clone();
				newChildren[childPos] = newChild.getRef();
				long newCount = count + newChild.count() - oldChild.count();
				return new BlobMap<K, V>(depth, prefixLength, entry, newChildren, mask, newCount);
			}
		}
	}

	@Override
	public <R> R reduceValues(BiFunction<? super R, ? super V, ? extends R> func, R initial) {
		if (entry != null) initial = func.apply(initial, entry.getValue());
		int n = children.length;
		for (int i = 0; i < n; i++) {
			initial = children[i].getValue().reduceValues(func, initial);
		}
		return initial;
	}

	@Override
	public <R> R reduceEntries(BiFunction<? super R, MapEntry<K, V>, ? extends R> func, R initial) {
		if (entry != null) initial = func.apply(initial, entry);
		int n = children.length;
		for (int i = 0; i < n; i++) {
			initial = children[i].getValue().reduceEntries(func, initial);
		}
		return initial;
	}
	
	@Override
	public BlobMap<K, V> filterValues(Predicate<V> pred) {
		BlobMap<K, V> r=this;
		for (int i=0; i<16; i++) {
			if (r==null) break; // might be null from dissoc
			BlobMap<K,V> oldChild=r.getChild(i);
			if (oldChild==null) continue;
			BlobMap<K,V> newChild=oldChild.filterValues(pred);
			r=r.withChild(i, oldChild, newChild);
		}
		
		// check entry at this level. A child might have moved here during the above loop!
		if (r!=null) {
			if ((r.entry!=null)&&!pred.test(r.entry.getValue())) r=r.dissoc(r.entry.getKey());
		}
		
		// check if whole blobmap was emptied
		if (r==null) {
			// everything deleted, but need 
			if (depth==0) r=empty();
		}
		return r;
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.BLOBMAP;
		return encodeRaw(bs,pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos = Format.writeVLCLong(bs,pos, count);
		if (count == 0) return pos; // nothing more to know... this must be the empty singleton

		pos = Format.writeVLCLong(bs,pos, depth);
		pos = Format.writeVLCLong(bs,pos, prefixLength);
			
		pos = MapEntry.encodeCompressed(entry,bs,pos); // entry may be null
		if (count == 1) return pos; // must be a single entry

		// finally write children
		pos = Utils.writeShort(bs,pos,mask);
		int n = children.length;
		for (int i = 0; i < n; i++) {
			pos = encodeChild(bs,pos,i);
		}
		return pos;
	}
	
	private int encodeChild(byte[] bs, int pos, int i) {
		Ref<BlobMap<K, V>> cref = children[i];
		return cref.encode(bs, pos);
		
		// TODO: maybe compress single entries?
//		ABlobMap<K, V> c=cref.getValue();
//		if (c.count==1) {
//			MapEntry<K,V> me=c.entryAt(0);
//			pos = me.getRef().encode(bs, pos);
//		} else {
//			pos = cref.encode(bs,pos);
//		}
//		return pos;
	}
	
	@Override
	public int estimatedEncodingSize() {
		return 100 + (children.length*2+1) * Format.MAX_EMBEDDED_LENGTH;
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static <K extends ABlob, V extends ACell> BlobMap<K, V> read(ByteBuffer bb) throws BadFormatException {
		long count = Format.readVLCLong(bb);
		if (count < 0) throw new BadFormatException("Negative count!");
		if (count == 0) return (BlobMap<K, V>) EMPTY;

		long depth = Format.readVLCLong(bb);
		if (depth < 0) throw new BadFormatException("Negative depth!");
		long prefixLength = Format.readVLCLong(bb);
		if (prefixLength < 0) throw new BadFormatException("Negative prefix length!");
		
		// Get entry at this node, might be null
		MapEntry<K, V> me = MapEntry.readCompressed(bb); 

		// single entry map
		if (count == 1) return new BlobMap<K, V>(depth, prefixLength, me, EMPTY_CHILDREN, (short) 0, 1L);

		short mask = bb.getShort();
		int n = Utils.bitCount(mask);
		Ref<BlobMap>[] children = new Ref[n];
		long childDepth=depth+prefixLength+1; // depth for children = this prefixDepth plus one extra hex digit
		for (int i = 0; i < n; i++) {
			children[i] = readChild(bb,childDepth);
		}
		return new BlobMap<K, V>(depth, prefixLength, me, children, mask, count);
	}
	
	@SuppressWarnings({ "rawtypes" })
	private static Ref<BlobMap> readChild(ByteBuffer bb, long childDepth) throws BadFormatException {
		Ref<BlobMap> ref = Format.readRef(bb);
		return ref;
		// TODO: compression of single entries?
//		ACell c=ref.getValue();
//		if (c instanceof BlobMap) {
//			return ref;
//		} else if (c instanceof AVector) {
//			AVector v=(AVector)c;
//			MapEntry me=MapEntry.convertOrNull(v);
//			if (me==null) throw new BadFormatException("Invalid MApEntry vector as BlobMap child");
//
//			return createAtDepth(me,childDepth).getRef();
//		} else {
//			throw new BadFormatException("Bad BlobMap child Type: "+RT.getType(c));
//		}
	}

	@Override
	protected MapEntry<K, V> getEntryByHash(Hash hash) {
		throw new UnsupportedOperationException();
	}

	@SuppressWarnings("unchecked")
	@Override
	public void validate() throws InvalidDataException {
		super.validate();

		long ecount = (entry == null) ? 0 : 1;
		int n = children.length;
		long pDepth = prefixDepth();
		for (int i = 0; i < n; i++) {
			ACell o = children[i].getValue();
			if (!(o instanceof BlobMap))
				throw new InvalidDataException("Illegal BlobMap child type: " + Utils.getClass(o), this);
			BlobMap<K, V> c = (BlobMap<K, V>) o;
			
			long ccount=c.count();
			if (ccount==0) {
				throw new InvalidDataException("Child "+i+" should not be empty! At depth "+depth,this);
			}

			if (c.depth != (pDepth+1)) {
				throw new InvalidDataException("Child must have depth: " + (pDepth+1) + " but was: " + c.depth,
						this);
			}

			if (c.prefixDepth() <= prefixDepth()) {
				throw new InvalidDataException("Child must have greater total prefix depth than " + prefixDepth()
						+ " but was: " + c.prefixDepth(), this);
			}
			c.validate();

			ecount += ccount;
		}

		if (count != ecount) throw new InvalidDataException("Bad entry count: " + ecount + " expected: " + count, this);
	}

	/**
	 * Gets the total depth of this node including prefix
	 * 
	 * @return
	 */
	private long prefixDepth() {
		return depth + prefixLength;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (prefixLength < 0) throw new InvalidDataException("Negative prefix length!" + prefixLength, this);
		if (count == 0) {
			if (this != EMPTY) throw new InvalidDataException("Non-singleton empty BlobMap", this);
			return;
		} else if (count == 1) {
			if (entry == null) throw new InvalidDataException("Single entry BlobMap with null entry?", this);
			if (mask != 0) throw new InvalidDataException("Single entry BlobMap with child mask?", this);
			return;
		}
		
		// at least count 2 from this point
		int cn = Utils.bitCount(mask);
		if (cn != children.length) throw new InvalidDataException(
				"Illegal mask: " + Utils.toHexString(mask) + " for given number of children: " + children.length, this);

		if (entry != null) {
			entry.validateCell();
			if (cn == 0)
				throw new InvalidDataException("BlobMap with entry and count=" + count + " must have children", this);
		} else {
			if (cn <= 1) throw new InvalidDataException(
					"BlobMap with no entry and count=" + count + " must have two or more children", this);
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public BlobMap<K, V> empty() {
		return (BlobMap<K, V>) EMPTY;
	}

	@Override
	public MapEntry<K, V> entryAt(long ix) {
		if (entry != null) {
			if (ix == 0L) return entry;
			ix -= 1;
		}
		int n = children.length;
		for (int i = 0; i < n; i++) {
			ABlobMap<K, V> c = children[i].getValue();
			long cc = c.count();
			if (ix < cc) return c.entryAt(ix);
			ix -= cc;
		}
		throw new IndexOutOfBoundsException(Errors.badIndex(ix));
	}

	/**
	 * Removes n leading entries from this BlobMap, in key order.
	 * 
	 * @param n Number of entries to remove
	 * @return Updated BlobMap with leading entries removed.
	 * @throws IndexOutOfBoundsException If there are insufficient entries in the
	 *                                   BlobMap
	 */
	public BlobMap<K, V> removeLeadingEntries(long n) {
		// TODO: optimise this
		BlobMap<K, V> bm = this;
		for (long i = 0; i < n; i++) {
			MapEntry<K, V> me = bm.entryAt(0);
			bm = bm.dissoc(me.getKey());
		}
		return bm;
	}
	
	/**
	 * Checks this BlobMap for equality with another map. 
	 * 
	 * @param a Map to compare with
	 * @return true if maps are equal, false otherwise.
	 */
	public boolean equals(AMap<K, V> a) {
		if (this == a) return true; // important optimisation for e.g. hashmap equality
		if (a == null) return false;
		if (this.getType()!=a.getType()) return false;
		// Must be a BlobMap
		return equals((BlobMap<K,V>)a);
	}
	
	/**
	 * Checks this BlobMap for equality with another BlobMap 
	 * 
	 * @param a BlobMap to compare with
	 * @return true if maps are equal, false otherwise.
	 */
	public boolean equals(BlobMap<K, V> a) {
		if (a==null) return false;
		long n=this.count();
		if (n != a.count()) return false;
		if (this.mask!=a.mask) return false;
		
		if (!Utils.equals(this.entry, a.entry)) return false;
		
		Hash h=this.cachedHash();
		if (h!=null) {
			Hash ha=a.cachedHash();
			if (ha!=null) return h.equals(ha);
		}
		return getHash().equals(a.getHash());
	}

	@Override
	public byte getTag() {
		return Tag.BLOBMAP;
	}

	@Override
	public ACell toCanonical() {
		return this;
	}

}
