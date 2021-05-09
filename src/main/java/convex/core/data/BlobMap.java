package convex.core.data;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

import convex.core.crypto.Hash;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;
import convex.core.util.Bits;
import convex.core.util.Errors;
import convex.core.util.Utils;

/**
 * BlobMap node implementation supporting: - An optional prefix string - An
 * optional entry with this prefix - Up to 16 child entries at the next level of
 * depth
 *
 * @param <V>
 */
public class BlobMap<K extends ABlob, V extends ACell> extends ABlobMap<K, V> {
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static final Ref<ABlobMap>[] EMPTY_CHILDREN = new Ref[0];

	public static final BlobMap<ABlob, ACell> EMPTY = new BlobMap<ABlob, ACell>(Blob.EMPTY, 0, 0, null, EMPTY_CHILDREN,
			(short) 0, 0L);

	/**
	 * Child entries, i.e. nodes with keys where this node is a common prefix
	 */
	private final Ref<ABlobMap<K, V>>[] children;

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
	 */
	private final long depth;

	/**
	 * Length of prefix. 0 = no prefix.
	 */
	private final long prefixLength;

	/**
	 * Prefix blob, must contain hex digits in the range [depth,depth+prefixLength).
	 * 
	 * Can be null in 
	 * 
	 * May contain more hex digits in memory, this is irrelevant from the
	 * perspective of serialisation.
	 * 
	 * Typically we populate with the key of the first entry added to avoid
	 * unnecessary blob instances being created.
	 */
	private final Blob prefix;

	@SuppressWarnings({ "rawtypes", "unchecked" })
	protected BlobMap(Blob prefix, long depth, long prefixLength, MapEntry<K, V> entry, Ref<ABlobMap>[] entries,
			short mask, long count) {
		super(count);
		this.prefix = prefix;
		this.depth = depth;
		this.prefixLength = prefixLength;
		this.entry = entry;
		int cn = Utils.bitCount(mask);
		if (cn != entries.length) throw new IllegalArgumentException(
				"Illegal mask: " + Utils.toHexString(mask) + " for given number of children: " + cn);
		this.children = (Ref[]) entries;
		this.mask = mask;
	}

	@SuppressWarnings("unchecked")
	public static <K extends ABlob, V extends ACell> BlobMap<K, V> create() {
		return (BlobMap<K, V>) EMPTY;
	}

	public static <K extends ABlob, V extends ACell> BlobMap<K, V> create(MapEntry<K, V> me) {
		Blob prefix = me.getKey().toBlob();
		long hexLength = prefix.hexLength();
		return new BlobMap<K, V>(prefix, 0, hexLength, me, EMPTY_CHILDREN, (short) 0, 1L);
	}

	private static <K extends ABlob, V extends ACell> BlobMap<K, V> createAtDepth(MapEntry<K, V> me, long depth) {
		Blob prefix = me.getKey().toBlob();
		long hexLength = prefix.hexLength();
		if (depth > hexLength)
			throw new IllegalArgumentException("Depth " + depth + " too deep for key with hexLength: " + hexLength);
		return new BlobMap<K, V>(prefix, depth, hexLength - depth, me, EMPTY_CHILDREN, (short) 0, 1L);
	}

	public static <K extends ABlob, V extends ACell> BlobMap<K, V> create(K k, V v) {
		Blob prefix=k.toBlob();
		MapEntry<K, V> me = MapEntry.create(k, v);
		long hexLength = k.hexLength();
		return new BlobMap<K, V>(prefix, 0, hexLength, me, EMPTY_CHILDREN, (short) 0, 1L);
	}
	
	public static <K extends ABlob, V extends ACell> BlobMap<K, V> of(Object k, Object v) {
		return create(RT.cvm(k),RT.cvm(v));
	}

	@Override
	public boolean isCanonical() {
		// TODO: rethink canonical definition
		// only canonical if depth is zero (May have a prefix)
		return depth == 0;
	}
	
	@Override public final boolean isCVMValue() {
		return true;
	}

	@SuppressWarnings("unchecked")
	@Override
	public BlobMap<K,V> updateRefs(IRefFunction func) {
		MapEntry<K, V> newEntry = (entry == null) ? null : entry.updateRefs(func);
		Ref<ABlobMap<K, V>>[] newChildren = Ref.updateRefs(children, func);
		if ((entry == newEntry) && (children == newChildren)) return this;
		return new BlobMap<K, V>(prefix, depth, prefixLength, newEntry, (Ref[])newChildren, mask, count);
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
		
		// check hex range
		if (!prefix.hexMatches(key,Utils.checkedInt(depth),Utils.checkedInt(pl))) return null;

		if (kl == pl) return entry; // we matched this key exactly!

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
	public BlobMap<K, V> assoc(ACell key, V value) {
		if (!(key instanceof ABlob)) return null;
		return assocEntry(MapEntry.create((K)key, value));
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Override
	public BlobMap<K, V> dissoc(ABlob k) {
		if (count <= 1) {
			if (count == 0) return this;
			if (entry.getKey().equals(k)) {
				return (BlobMap<K, V>) EMPTY;
			}
			return this;
		}
		long pDepth = prefixLength + depth; // hex depth of this node including prefix
		long kl = k.hexLength(); // hex length of key to dissoc
		if (kl < pDepth) {
			// no match for sure, so no change
			return this;
		}
		if (kl == pDepth) {
			// need to check for match with current entry
			if (entry == null) return this;
			if (!prefix.hexEquals(k, depth, prefixLength)) return this;
			// at this point have matched entry exactly. So need to remove it safely while
			// preserving invariants
			if (children.length == 1) {
				// need to promote child to the current depth
				BlobMap<K, V> c = (BlobMap<K, V>) children[0].getValue();
				return new BlobMap(c.getPrefix(), depth, c.depth + c.prefixLength - depth, c.entry, c.children, c.mask,
						count - 1);
			} else {
				// just clear current entry
				return new BlobMap(prefix, depth, prefixLength, null, children, mask, count - 1);
			}
		}
		// dissoc beyond current prefix length, so need to check children
		int digit = k.getHexDigit(pDepth);
		int childIndex = Bits.indexForDigit(digit, mask);
		if (childIndex < 0) return this; // key miss
		// we know we need to replace a child
		BlobMap<K, V> oldChild = (BlobMap<K, V>) children[childIndex].getValue();
		BlobMap<K, V> newChild = oldChild.dissoc(k);
		return this.withChild(digit, oldChild, newChild);
	}

	private Blob getPrefix() {
		return prefix;
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
				BlobMap<K, V> split = new BlobMap<K, V>(prefix, mkl, pDepth - mkl, entry, (Ref[]) children, mask,
						count);
				int splitDigit = prefix.getHexDigit(mkl);
				short splitMask = (short) (1 << splitDigit);
				BlobMap<K, V> result = new BlobMap<K, V>(k.toBlob(), depth, mkl - depth, e, new Ref[] { split.getRef() },
						splitMask, count + 1);
				return result;
			} else {
				// we need to fork the current prefix in two at position mkl
				BlobMap<K, V> branch1 = new BlobMap<K, V>(prefix, mkl, pDepth - mkl, entry, (Ref[]) children, mask,
						count);
				BlobMap<K, V> branch2 = new BlobMap<K, V>(k.toBlob(), mkl, newKeyLength - mkl, e, (Ref[]) EMPTY_CHILDREN,
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
				BlobMap<K, V> fork = new BlobMap<K, V>(k.toBlob(), depth, mkl - depth, null, newChildren, newMask, count + 1L);
				return fork;
			}
		}
		assert (newKeyLength >= pDepth);
		if (newKeyLength == pDepth) {
			// we must have matched the current entry exactly
			if (entry == null) {
				// just add entry at this position
				return new BlobMap<K, V>(k.toBlob(), depth, prefixLength, e, (Ref[]) children, mask, count + 1);
			}
			if (entry == e) return this;

			// swap entry, no need to change count
			return new BlobMap<K, V>(k.toBlob(), depth, prefixLength, e, (Ref[]) children, mask, count);
		}
		// at this point we have matched full prefix, but new key length is longer.
		// so we need to update (or add) exactly one child
		int childDigit = k.getHexDigit(pDepth);
		BlobMap<K, V> oldChild = getChild(childDigit);
		BlobMap<K, V> newChild;
		if (oldChild == null) {
			newChild = createAtDepth(e, pDepth);
		} else {
			newChild = oldChild.assocEntry(e);
		}
		return withChild(childDigit, oldChild, newChild);
	}

	/**
	 * Updates this BlobMap with a new child.
	 * 
	 * Either oldChild or newChild may be null. Empty maps are treated as null.
	 * 
	 * @param childDigit Digit for new child
	 * @param newChild
	 * @return
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private BlobMap<K, V> withChild(int childDigit, BlobMap<K, V> oldChild, BlobMap<K, V> newChild) {
		// consider empty children as null
		if (oldChild == EMPTY) oldChild = null;
		if (newChild == EMPTY) newChild = null;
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
			return new BlobMap<K, V>(prefix, depth, prefixLength, entry, newChildren, newMask,
					count + newChild.count());
		} else {
			if (newChild == null) {
				// need to delete a child
				int delPos = Bits.positionForDigit(childDigit, mask);

				// handle special case where we need to promote the remaining child
				if ((entry == null) && (n == 2)) {
					BlobMap<K, V> rm = (BlobMap<K, V>) children[1 - delPos].getValue();
					long newPLength = prefixLength + rm.prefixLength;
					return new BlobMap<K, V>(rm.getPrefix(), depth, newPLength, rm.entry, (Ref[]) rm.children, rm.mask,
							rm.count());
				}

				newChildren = new Ref[n - 1];
				short newMask = (short) (mask & ~(1 << childDigit));
				System.arraycopy(children, 0, newChildren, 0, delPos); // earlier entries
				System.arraycopy(children, delPos + 1, newChildren, delPos, n - delPos - 1); // later entries
				return new BlobMap<K, V>(prefix, depth, prefixLength, entry, newChildren, newMask,
						count - oldChild.count());
			} else {
				// need to replace a child
				int childPos = Bits.positionForDigit(childDigit, mask);
				newChildren = children.clone();
				newChildren[childPos] = newChild.getRef();
				long newCount = count + newChild.count() - oldChild.count();
				return new BlobMap<K, V>(prefix, depth, prefixLength, entry, newChildren, mask, newCount);
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
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.BLOBMAP;
		return encodeRaw(bs,pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos = Format.writeVLCLong(bs,pos, count);
		if (count == 0) return pos; // nothing more to know... this is the empty singleton

		pos = Format.writeHexDigits(bs,pos, prefix, depth, prefixLength);
		pos = Format.write(bs,pos, entry); // entry may be null
		if (count == 1) return pos; // must be a single entry

		// finally write children
		pos = Utils.writeShort(bs,pos,mask);
		int n = children.length;
		for (int i = 0; i < n; i++) {
			pos = children[i].encode(bs,pos);
		}

		return pos;
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

		byte[] pbs = Format.readHexDigits(bb, depth, prefixLength);
		Blob prefix = Blob.wrap(pbs);
		
		// Get entry at this node, might be null
		MapEntry<K, V> me = Format.read(bb); 

		// single entry map
		if (count == 1) return new BlobMap<K, V>(prefix, depth, prefixLength, me, EMPTY_CHILDREN, (short) 0, 1L);

		short mask = bb.getShort();
		int n = Utils.bitCount(mask);
		Ref<ABlobMap>[] children = new Ref[n];
		for (int i = 0; i < n; i++) {
			Ref<ABlobMap> ref = Format.readRef(bb);
			children[i] = ref;
		}
		return new BlobMap<K, V>(prefix, depth, prefixLength, me, children, mask, count);
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
			Object o = children[i].getValue();
			if (!(o instanceof BlobMap))
				throw new InvalidDataException("Illegal BlobMap child type: " + Utils.getClass(o), this);
			BlobMap<K, V> c = (BlobMap<K, V>) o;

			if (c.depth != pDepth) {
				throw new InvalidDataException("Child must have depth: " + prefixDepth() + " but was: " + c.depth,
						this);
			}

			if (c.prefixDepth() <= prefixDepth()) {
				throw new InvalidDataException("Child must have greater total prefix depth than " + prefixDepth()
						+ " but was: " + c.prefixDepth(), this);
			}
			c.validate();

			ecount += c.count();
		}

		if (pDepth > prefix.length() * 2) {
			throw new InvalidDataException("Insufficient prefix size for prefix depth " + pDepth, this);
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

	@Override
	public byte getTag() {
		return Tag.BLOBMAP;
	}

}
