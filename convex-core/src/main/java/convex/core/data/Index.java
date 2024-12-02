package convex.core.data;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Predicate;

import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;
import convex.core.util.Bits;
import convex.core.util.Utils;

/**
 * Index node implementation, providing an efficient radix tree based immutable data structure for indexed access and sorting.
 * 
 * Supporting: 
 * 
 * <ul>
 * <li>An optional prefix string</li>
 * <li>An optional entry with this exact prefix </li>
 * <li>Up to 16 child entries at the next level of depth</li>
 * </ul>
 * @param <K> Type of Keys
 * @param <V> Type of values
 */
public final class Index<K extends ABlobLike<?>, V extends ACell> extends AIndex<K, V> {
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static final Ref<Index>[] EMPTY_CHILDREN = new Ref[0];
	
	/**
	 *  Maximum depth of index, in hex digits
	 */
	private static final int MAX_DEPTH=64;
	
	/**
	 *  Maximum usable size of keys, in bytes
	 */
	private static final int MAX_KEY_BYTES=MAX_DEPTH/2;

	/**
	 * Empty Index singleton
	 */
	public static final Index<?, ?> EMPTY = Cells.intern(new Index<ABlob, ACell>(0, null, EMPTY_CHILDREN,(short) 0, 0L));
	
	/**
	 * Entry for this node of the radix tree. Invariant assumption that the prefix
	 * is correct. Will be null if there is no entry at this node.
	 */
	private final MapEntry<K, V> entry;

	/**
	 * Depth of radix tree entry in number of hex digits.
	 */
	private final long depth;

	/**
	 * Mask of child entries, 16 bits for each hex digit that may be present.
	 */
	private final short mask;
	
	/**
	 * Child entries, i.e. nodes with keys where this node is a common prefix. Only contains children where mask is set.
	 * Child entries must have at least one entry.
	 */
	private final Ref<Index<K, V>>[] children;

	@SuppressWarnings({ "rawtypes", "unchecked" })
	protected Index(long depth, MapEntry<K, V> entry, Ref<Index>[] entries,
			short mask, long count) {
		super(count);
		this.depth = depth;
		this.entry = entry;
		this.children = (Ref[]) entries;
		this.mask = mask;
	}
	
	@SuppressWarnings("rawtypes")
	public  static <K extends ABlobLike<?>, V extends ACell> Index<K, V> unsafeCreate(long depth, MapEntry<K, V> entry, Ref<Index>[] entries,
			int mask, long count) {
		return new Index<K,V>(depth,entry,entries,(short)mask,count);
	}

	@SuppressWarnings("unchecked")
	public static <K extends ABlobLike<?>, V extends ACell> Index<K, V> create(MapEntry<K, V> me) {
		ACell k=me.getKey();
		if (!(k instanceof ABlobLike)) return null; // check in case invalid key type
		long depth = effectiveLength((K)k);
		return new Index<K, V>(depth, me, EMPTY_CHILDREN, (short) 0, 1L);
	}

	public static <K extends ABlobLike<?>, V extends ACell> Index<K, V> create(K k, V v) {
		MapEntry<K, V> me = MapEntry.create(k, v);
		long hexLength = effectiveLength(k);
		return new Index<K, V>(hexLength, me, EMPTY_CHILDREN, (short) 0, 1L);
	}
	
	public static <K extends ABlobLike<?>, V extends ACell> Index<K, V> of(Object k, Object v) {
		return create(RT.cvm(k),RT.cvm(v));
	}
	
	@SuppressWarnings("unchecked")
	public static <K extends ABlobLike<?>, V extends ACell> Index<K, V> of(Object... kvs) {
		int n = kvs.length;
		if (Utils.isOdd(n)) throw new IllegalArgumentException("Even number of key + values required");
		Index<K, V> result = (Index<K, V>) EMPTY;
		for (int i = 0; i < n; i += 2) {
			V value=RT.cvm(kvs[i + 1]);
			result = result.assoc((K) kvs[i], value);
		}

		return (Index<K, V>) result;
	}

	@Override
	public boolean isCanonical() {
		return true;
	}
	
	@Override public final boolean isCVMValue() {
		return true;
	}

	@SuppressWarnings("unchecked")
	@Override
	public Index<K,V> updateRefs(IRefFunction func) {
		MapEntry<K, V> newEntry = Ref.update(entry,func);
		Ref<Index<K, V>>[] newChildren = Ref.updateRefs(children, func);
		if ((entry == newEntry) && (children == newChildren)) return this;
		Index<K,V> result= new Index<K, V>(depth, newEntry, (Ref[])newChildren, mask, count);
		result.attachEncoding(encoding); // this is an optimisation to avoid re-encoding
		return result;
	}

	@Override
	public V get(K key) {
		MapEntry<K, V> me = getEntry(key);
		if (me == null) return null;
		return me.getValue();
	}

	@Override
	public MapEntry<K, V> getEntry(K key) {
		long kl = key.hexLength();
		long pl = depth;
		if (kl < pl) return null; // key is too short to start with current prefix

		if (kl == pl) {
			if (entry!=null) {
				K ekey=entry.getKey();
				if (keyMatch(key,ekey)) return entry; // we matched this key exactly!
			}
			if (pl<MAX_DEPTH) return null; // entry definitely does not exist
		}
		
		// key length is longer than current prefix
		// if we are max depth, return entry iff matches up to full depth
		if (pl==MAX_DEPTH) {
			if (entry==null) return null;
			if (key.hexMatch(entry.getKey().toBlob(),0,MAX_DEPTH)==MAX_DEPTH) return entry;
			return null; 
		}

		// key exceeds current prefix, so need to go to next branch of tree
		int digit = key.getHexDigit(pl);
		Index<K, V> cc = getChild(digit);

		if (cc == null) return null;
		return cc.getEntry(key);
	}

	/**
	 * Gets the child for a specific digit, or null if not found
	 * 
	 * @param digit
	 * @return
	 */
	private Index<K, V> getChild(int digit) {
		int i = Bits.indexForDigit(digit, mask);
		if (i < 0) return null;
		return (Index<K, V>) children[i].getValue();
	}

	@Override
	public int getRefCount() {
		// note entry might be null
		return Cells.refCount(entry) + children.length;
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
	public Index<K, V> assoc(ACell key, ACell value) {
		if (!(key instanceof ABlobLike)) return null;
		return assocEntry(MapEntry.create((K)key, (V)value));
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Override
	public Index<K, V> dissoc(K k) {
		if (count <= 1) {
			if (count == 0) return this; // Must already be empty singleton
			if (keyMatch(k,entry.getKey())) {
				return empty();
			}
			return this; // leave existing entry in place
		}
		long pDepth = depth; // hex depth of this node including prefix
		long kl = effectiveLength(k);; // hex length of key to dissoc
		if (kl < pDepth) {
			// no match for sure, so no change
			return this;
		}
		if (kl == pDepth) {
			// need to check for match with current entry
			if (entry == null) return this;
			if (!keyMatch(k,entry.getKey())) return this;
			// at this point have matched entry exactly. So need to remove it safely while
			// preserving invariants
			if (children.length == 1) {
				Index<K, V> c = (Index<K, V>) children[0].getValue();
				return c;
			} else {
				// Clearing current entry, keeping existing children (must be 2+)
				return new Index(depth, null, children, mask, count - 1);
			}
		}
		// dissoc beyond current prefix length, so need to check children
		int digit = k.getHexDigit(pDepth);
		int childIndex = Bits.indexForDigit(digit, mask);
		if (childIndex < 0) return this; // key miss
		// we know we need to replace a child
		Index<K, V> oldChild = (Index<K, V>) children[childIndex].getValue();
		Index<K, V> newChild = oldChild.dissoc(k);
		Index<K,V> r=this.withChild(digit, oldChild, newChild);
		
		return r;
	}

	/**
	 * Tests if two keys match (up to the maximum index key depth)
	 * @param a First key
	 * @param b second key
	 * @return True if keys match
	 */
	public static <K extends ABlobLike<?>>boolean keyMatch(K a, K b) {
		long n=a.count();
		if (n<MAX_KEY_BYTES) {
			return a.equalsBytes(b.toBlob());
		}
		if (b.count()<MAX_KEY_BYTES) return false;
		return a.hexMatch(b.toBlob(), 0, MAX_DEPTH)==MAX_DEPTH;
		
	}

	/**
	 * Common Prefix blob, must contain hex digits in range [0,depth).
	 * 
	 * May contain more hex digits, this is irrelevant from the
	 * perspective of serialisation.
	 * 
	 * Typically we populate with the key of the first entry added to avoid
	 * unnecessary blob instances being created.
	 */
	private ABlobLike<?> getPrefix() {
		if (entry!=null) return entry.getKey();
		int n=children.length;
		if (n==0) return Blob.EMPTY;
		return children[0].getValue().getPrefix();
	}

	@Override
	protected void accumulateEntries(Collection<Entry<K, V>> h) {
		for (int i = 0; i < children.length; i++) {
			children[i].getValue().accumulateEntries(h);
		}
		if (entry != null) h.add(entry);
	}

	@Override
	protected void accumulateKeySet(Set<K> h) {
		for (int i = 0; i < children.length; i++) {
			children[i].getValue().accumulateKeySet(h);
		}
		if (entry != null) h.add(entry.getKey());
	}

	@Override
	protected void accumulateValues(java.util.List<V> al) {
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

	@Override
	public Index<K, V> assocEntry(MapEntry<K, V> e) {
		return assocEntry(e,0);
	}
	
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private Index<K, V> assocEntry(MapEntry<K, V> e, long match) {

		if (count == 0L) return create(e);
		if (count == 1L) {
			assert (mask == (short) 0); // should be no children
			if (entry.keyEquals(e)) {
				if (entry == e) return this;
				// recreate, preserving current depth
				return create(e);
			}
		}
		ACell maybeValidKey=e.getKey();
		if (!(maybeValidKey instanceof ABlobLike)) return null; // invalid key type!
		ABlobLike<?> k = (ABlobLike)maybeValidKey;
		
		long newKeyLength = effectiveLength(k);; // hex length of new key, up to MAX_DEPTH
		long mkl; // matched key length
		ABlobLike prefix=getPrefix(); // prefix of current node (valid up to pDepth)
		if (newKeyLength >= depth) {
			// constrain relevant key length by match with current prefix
			mkl = match + k.hexMatch(prefix, match, depth-match);
		} else {
			mkl = match + k.hexMatch(prefix, match, newKeyLength - match);
		}
		if (mkl < depth) {
			// we collide at a point shorter than the current prefix length
			if (mkl == newKeyLength) {
				// new key is subset of the current prefix, so split prefix at key position mkl
				// doesn't need to adjust child depths, since they are splitting at the same
				// point
				int splitDigit = prefix.getHexDigit(mkl);
				short splitMask = (short) (1 << splitDigit);
				Index<K, V> result = new Index<K, V>(mkl, e, new Ref[] { this.getRef() }, splitMask, count + 1);
				return result;
			} else {
				// we need to fork the current prefix in two at position mkl			
				Index<K, V> branch1 = this;
				Index<K, V> branch2 = create(e);
				int d1 = prefix.getHexDigit(mkl);
				int d2 = k.getHexDigit(mkl);
				if (d1 > d2) {
					// swap to get in right order
					Index<K, V> temp = branch1;
					branch1 = branch2;
					branch2 = temp;
				}
				Ref[] newChildren = new Ref[] { branch1.getRef(), branch2.getRef() };
				short newMask = (short) ((1 << d1) | (1 << d2));
				Index<K, V> fork = new Index<K, V>(mkl, null, newChildren, newMask, count + 1L);
				return fork;
			}
		}
		assert (newKeyLength >= depth);
		if (newKeyLength == depth) {
			// we must have matched the current entry exactly
			if (entry == null) {
				// just add entry at this position
				return new Index<K, V>(depth, e, (Ref[]) children, mask, count + 1);
			}
			if (entry == e) return this;

			// swap entry, no need to change count
			return new Index<K, V>(depth, e, (Ref[]) children, mask, count);
		}
		// at this point we have matched full prefix, but new key length is longer.
		// so we need to update (or add) exactly one child
		int childDigit = k.getHexDigit(depth);
		Index<K, V> oldChild = getChild(childDigit);
		Index<K, V> newChild;
		if (oldChild == null) {
			newChild = create(e); // Must be at least 1 beyond current prefix. Safe because pDepth < MAX_DEPTH
		} else {
			newChild = oldChild.assocEntry(e);
		}
		return withChild(childDigit, oldChild, newChild); // can't be null since associng
	}

	/**
	 * Updates this Index with a new child.
	 * 
	 * Either oldChild or newChild may be null. Empty maps are treated as null.
	 * 
	 * @param childDigit Digit for new child
	 * @param newChild
	 * @return Index with child removed, or null if Index was deleted entirely
	 */
	@SuppressWarnings({ "rawtypes", "unchecked", "null"})
	private Index<K, V> withChild(int childDigit, Index<K, V> oldChild, Index<K, V> newChild) {
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
			return new Index<K, V>(depth, entry, newChildren, newMask,
					count + newChild.count());
		} else {
			// dealing with an existing child
			if (newChild == null) {
				// need to delete an existing child
				int delPos = Bits.positionForDigit(childDigit, mask);

				// handle special case where entry is null and we need to promote the one remaining child
				if (entry == null) {
					if (n == 2) {
						Index<K, V> rm = (Index<K, V>) children[1 - delPos].getValue();
						return rm;
					} 
				}
				newChildren = new Ref[n - 1];
				short newMask = (short) (mask & ~(1 << childDigit));
				System.arraycopy(children, 0, newChildren, 0, delPos); // earlier entries
				System.arraycopy(children, delPos + 1, newChildren, delPos, n - delPos - 1); // later entries
				return new Index<K, V>(depth, entry, newChildren, newMask,
						count - oldChild.count());
			} else {
				// need to replace a child
				int childPos = Bits.positionForDigit(childDigit, mask);
				newChildren = children.clone();
				newChildren[childPos] = newChild.getRef();
				long newCount = count + newChild.count() - oldChild.count();
				return new Index<K, V>(depth, entry, newChildren, mask, newCount);
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
	public Index<K, V> filterValues(Predicate<V> pred) {
		Index<K, V> r=this;
		for (int i=0; i<16; i++) {
			if (r==null) break; // might be null from dissoc
			Index<K,V> oldChild=r.getChild(i);
			if (oldChild==null) continue;
			Index<K,V> newChild=oldChild.filterValues(pred);
			r=r.withChild(i, oldChild, newChild);
		}
		
		// check entry at this level. A child might have moved here during the above loop!
		if (r!=null) {
			if ((r.entry!=null)&&!pred.test(r.entry.getValue())) r=r.dissoc(r.entry.getKey());
		}
		
		// check if whole Index was emptied
		if (r==null) {
			// everything deleted, but need 
			return empty();
		}
		return r;
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.INDEX;
		return encodeRaw(bs,pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos = Format.writeVLQCount(bs,pos, count);
		if (count == 0) return pos; // nothing more to know... this must be the empty singleton

		if (count == 1) {
			// directly encode single entry
			pos=entry.getKeyRef().encode(bs,pos);
			pos=entry.getValueRef().encode(bs,pos);
			return pos; // must be a single entry, exit early
		} else {
			if (entry==null) {
				bs[pos++]=Tag.NULL; // no entry present
			} else {
				bs[pos++]=Tag.VECTOR;
				pos=entry.getKeyRef().encode(bs,pos);
				pos=entry.getValueRef().encode(bs,pos);
			}
		}

		// We only have a meaningful depth if more than one entry
		bs[pos++] = (byte)depth;
		
		// write mask
		pos = Utils.writeShort(bs,pos,mask);

		// finally write children
		int n = children.length;
		for (int i = 0; i < n; i++) {
			pos = encodeChild(bs,pos,i);
		}
		return pos;
	}
	
	private int encodeChild(byte[] bs, int pos, int i) {
		Ref<Index<K, V>> cref = children[i];
		return cref.encode(bs, pos);
		
		// TODO: maybe compress single entries?
//		AIndex<K, V> c=cref.getValue();
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
	public static <K extends ABlobLike<?>, V extends ACell> Index<K, V> read(Blob b, int pos) throws BadFormatException {
		long count = Format.readVLQCount(b,pos+1);
		if (count < 0) throw new BadFormatException("Negative count!");
		if (count == 0) return (Index<K, V>) EMPTY;
		
		// index for reading
		int epos=pos+1+Format.getVLQCountLength(count);
		
		MapEntry<K,V> me;
		boolean hasEntry;
		if (count==1) {
			hasEntry=true;
		} else {
			byte c=b.byteAt(epos++); // Read byte
			switch (c) {
			case Tag.NULL: hasEntry=false; break;
			case Tag.VECTOR: hasEntry=true; break;
			default: throw new BadFormatException("Invalid MapEntry tag in Index: "+c);
			}
		}
		if (hasEntry) {
			Ref<K> kr=Format.readRef(b,epos);
			epos+=kr.getEncodingLength();
			Ref<V> vr=Format.readRef(b,epos);
			epos+=vr.getEncodingLength();
			me=MapEntry.fromRefs(kr, vr);
			
			if (count == 1) {
				// single entry map, doesn't need separate depth encoding
				long depth=kr.isEmbedded()?kr.getValue().hexLength():MAX_DEPTH;
				Index<K,V> result = new Index<K, V>(depth, me, EMPTY_CHILDREN, (short) 0, 1L);
				result.attachEncoding(b.slice(pos, epos));
				return result;
			} 
		} else {
			me=null;
		}

		Index<K,V> result;
		int depth = 0xFF & b.byteAt(epos);
		if (depth >=MAX_DEPTH) {
			if (depth==MAX_DEPTH) throw new BadFormatException("More than one entry and MAX_DEPTH");
			throw new BadFormatException("Excessive depth!");
		}
		epos+=1;

		// Need to include children
		short mask = b.shortAt(epos);
		epos+=2;
		int n = Utils.bitCount(mask);
		Ref<Index>[] children = new Ref[n];
		for (int i = 0; i < n; i++) {
			Ref<Index> cr=Format.readRef(b,epos);
			epos+=cr.getEncodingLength();
			children[i] =cr; 
		}
		result= new Index<K, V>(depth, me, children, mask, count);
		result.attachEncoding(b.slice(pos, epos));
		return result;
	}

	@Override
	protected MapEntry<K, V> getEntryByHash(Hash hash) {
		throw new UnsupportedOperationException();
	}

	@SuppressWarnings("unchecked")
	@Override
	public void validate() throws InvalidDataException {
		super.validate();
		
		if ((depth<0)||(depth>MAX_DEPTH)) throw new InvalidDataException("Invalid index depth",this);
		
		if (entry!=null) {
			ABlobLike<K> k=RT.ensureBlobLike(entry.getKey());
			if (k==null) throw new InvalidDataException("Invalid entry key type: "+Utils.getClassName(entry.getKey()),this);
			if (depth!=effectiveLength(k)) throw new InvalidDataException("Entry at inconsistent depth",this);
		}
		
		ABlobLike<?> prefix=getPrefix();
		if (depth>effectiveLength(prefix)) throw new InvalidDataException("depth longer than common prefix",this);

		long ecount = (entry == null) ? 0 : 1;
		int n = children.length;
		for (int i = 0; i < n; i++) {
			ACell o = children[i].getValue();
			if (!(o instanceof Index))
				throw new InvalidDataException("Illegal Index child type: " + Utils.getClass(o), this);
			Index<K, V> c = (Index<K, V>) o;
			
			long ccount=c.count();
			if (ccount==0) {
				throw new InvalidDataException("Child "+i+" should not be empty! At depth "+depth,this);
			}
			
			if (c.getDepth() <= getDepth()) {
				throw new InvalidDataException("Child must have greater depth than parent", this);
			}
			
			ABlobLike<?> childPrefix=c.getPrefix();
			long ml=prefix.hexMatch(childPrefix, 0, depth);
			if (ml<depth) throw new InvalidDataException("Child does not have matching common prefix", this);

			c.validate();
			
			// check child has correct digit for mask position
			int digit=childPrefix.getHexDigit(depth);
			if (i!=Bits.indexForDigit(digit, mask)) throw new InvalidDataException("Child does not have correct digit", this);

			ecount += ccount;
		}

		if (count != ecount) throw new InvalidDataException("Bad entry count: " + ecount + " expected: " + count, this);
	}

	private static long effectiveLength(ABlobLike<?> prefix) {
		return Math.min(MAX_DEPTH, prefix.hexLength());
	}

	/**
	 * Gets the depth of this Index node, i.e. the hex length of the common prefix (up to MAX_DEPTH)
	 * 
	 * @return
	 */
	long getDepth() {
		return depth;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (count == 0) {
			if (this != EMPTY) throw new InvalidDataException("Non-singleton empty Index", this);
			return;
		} else if (count == 1) {
			if (entry == null) throw new InvalidDataException("Single entry Index with null entry?", this);
			if (mask != 0) throw new InvalidDataException("Single entry Index with child mask?", this);
			return;
		}
		
		long pDepth=getDepth();
		if (pDepth>MAX_DEPTH) throw new InvalidDataException("Excessive Prefix Depth beyond MAX_DEPTH", this);
		if (pDepth==MAX_DEPTH) {
			if (count!=1) throw new InvalidDataException("Can only have a single entry at MAX_DEPTH",this);
		}
		
		// at least count 2 from this point
		int cn = Utils.bitCount(mask);
		if (cn != children.length) throw new InvalidDataException(
				"Illegal mask: " + Utils.toHexString(mask) + " for given number of children: " + children.length, this);

		if (entry != null) {
			entry.validateCell();
			long entryKeyLength=entry.getKey().hexLength();
			if (entryKeyLength<pDepth) throw new InvalidDataException("Key too short for prefix depth",this);
			if (entryKeyLength>MAX_DEPTH) {
				if (pDepth!=MAX_DEPTH) throw new InvalidDataException("Key too long at this prefix depth",this);
			}
			if (cn == 0)
				throw new InvalidDataException("Index with entry and count=" + count + " must have children", this);
		} else {
			if (cn <= 1) throw new InvalidDataException(
					"Index with no entry and count=" + count + " must have two or more children", this);
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public Index<K, V> empty() {
		return (Index<K, V>) EMPTY;
	}
	
	@SuppressWarnings("unchecked")
	public static <K extends ABlobLike<?>, V extends ACell> Index<K, V> none() {
		return (Index<K, V>) EMPTY;
	}

	@Override
	public MapEntry<K, V> entryAt(long ix) {
		if (entry != null) {
			if (ix == 0L) return entry;
			ix -= 1;
		}
		int n = children.length;
		for (int i = 0; i < n; i++) {
			Index<K, V> c = children[i].getValue();
			long cc = c.count();
			if (ix < cc) return c.entryAt(ix);
			ix -= cc;
		}
		throw new IndexOutOfBoundsException((int)ix);
	}

	/**
	 * Slices this Index, starting at the specified position
	 * 
	 * Removes n leading entries from this Index, in key order.
	 * 
	 * @param start Start position of entries to keep
	 * @return Updated Index with leading entries removed, or null if invalid slice
	 */
	@Override
	public Index<K, V> slice(long start) {
		return slice(start,count);
	}
	
	/**
	 * Returns a slice of this Index
	 * 
	 * @param start Start position of slice (inclusive)
	 * @param end End position of slice (exclusive)
	 * @return Slice of Index, or null if invalid slice
	 */
	@Override
	public Index<K, V> slice(long start, long end) {
		if ((start<0)||(end>count)) return null;
		if (end<start) return null;
		long n=end-start;
		if (n==0) return empty();
		if (n==count) return this;
		
		// TODO: optimise this
		Index<K, V> bm = this;
		for (long i=count-1; i>=end; i--) {
			MapEntry<K, V> me = bm.entryAt(i);
			bm = bm.dissoc(me.getKey());
		}
		
		for (long i = 0; i < start; i++) {
			MapEntry<K, V> me = bm.entryAt(0);
			bm = bm.dissoc(me.getKey());
		}
		return bm;
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public boolean equals(ACell a) {
		if (this == a) return true; // important optimisation for e.g. hashmap equality
		if (!(a instanceof Index)) return false;
		// Must be a Index
		return equals((Index<K,V>)a);
	}
	
	/**
	 * Checks this Index for equality with another Index 
	 * 
	 * @param a Index to compare with
	 * @return true if maps are equal, false otherwise.
	 */
	public boolean equals(Index<K, V> a) {
		if (a==null) return false;
		long n=this.count();
		if (n != a.count()) return false;
		if (this.mask!=a.mask) return false;
		
		if (!Cells.equals(this.entry, a.entry)) return false;
		
		return getHash().equals(a.getHash());
	}

	@Override
	public byte getTag() {
		return Tag.INDEX;
	}

	@Override
	public ACell toCanonical() {
		return this;
	}

	@Override
	public boolean containsValue(ACell value) {
		if ((entry!=null)&&Cells.equals(value, entry.getValue())) return true;
		for (Ref<Index<K,V>> cr : children) {
			if (cr.getValue().containsValue(value)) return true;
		}
		return false;
	}

	@SuppressWarnings("unchecked")
	public static <R extends AIndex<K, V>, K extends ABlobLike<?>, V extends ACell> R create(HashMap<K, V> map) {
		Index<K,V> result=(Index<K, V>) EMPTY;
		for (Map.Entry<K,V> me: map.entrySet()) {
			result=result.assoc(me.getKey(), me.getValue());
			if (result==null) return null;
		}
		return (R) result;
	}
	
	@SuppressWarnings("unchecked")
	public static <R extends AIndex<K, V>, K extends ABlobLike<?>, V extends ACell> R create(AHashMap<K, V> map) {
		Index<K,V> result=(Index<K, V>) EMPTY;
		long n=map.count();
		for (long i=0; i<n; i++) {
			MapEntry<K,V> me=map.entryAt(i);
			result=result.assoc(me.getKey(), me.getValue());
			if (result==null) return null;
		}
		return (R) result;
	}

	public HashMap<K, V> toHashMap() {
		int n=size();
		HashMap<K, V> hm=new HashMap<>(n);
		for (int i=0; i<n; i++) {
			MapEntry<K, V> entry=entryAt(i);
			K key=entry.getKey();
			hm.put(key, entry.getValue());
		}
		return hm;
	}

}
