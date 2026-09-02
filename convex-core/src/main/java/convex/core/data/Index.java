package convex.core.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.TODOException;
import convex.core.lang.RT;
import convex.core.util.Bits;
import convex.core.util.MergeFunction;
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
	/**
	 * Maximum depth handled with the original recursive algorithms. Keeping this
	 * at the historical Index limit means ordinary keys take exactly the old path;
	 * explicit stacks are reserved for extended-depth indexes.
	 */
	private static final int MAX_RECURSIVE_DEPTH=64;

	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static final Ref<Index>[] EMPTY_CHILDREN = new Ref[0];
	
	/**
	 * Maximum depth of an Index key, in hex digits. This permits keys up to
	 * 255 bytes, matching the common filesystem NAME_MAX boundary.
	 */
	public static final int MAX_DEPTH=510;
	
	/**
	 *  Maximum usable size of keys, in bytes
	 */
	private static final int MAX_KEY_BYTES=MAX_DEPTH/2;

	/**
	 * Empty Index singleton
	 */
	public static final Index<?, ?> EMPTY = Cells.intern(new Index<ABlobLike<?>, ACell>(0, null, EMPTY_CHILDREN,(short) 0, 0L));
	
	/**
	 * Entry for this node of the radix tree. Invariant assumption that the prefix
	 * is correct. Will be null if there is no entry at this node.
	 */
	private final MapEntry<K, V> entry;

	/**
	 * Depth of radix tree entry in number of hex digits.
	 *
	 * <p>A single-entry node does not encode its depth: it is the hex length of the
	 * entry key. When such a node is decoded with a non-embedded key the key is not
	 * loaded at decode time, so the depth is held as {@link #UNRESOLVED_DEPTH} and
	 * derived from the key on first use by {@link #getDepth()}. The cached value is
	 * idempotent and int writes are atomic, so the unsynchronised write is benign.</p>
	 */
	private int depth;

	/** Depth of a decoded single-entry node whose key has not yet been loaded. */
	static final int UNRESOLVED_DEPTH=-1;

	/**
	 * Mask of child entries, 16 bits for each hex digit that may be present.
	 */
	private final short mask;
	
	/**
	 * Child entries, i.e. nodes with keys where this node is a common prefix. Only contains children where mask is set.
	 * Child entries must have at least one entry.
	 */
	private final Ref<Index<K, V>>[] children;

	/**
	 * Cached prefix blob for entry-less nodes, lazily computed by getPrefix().
	 * Not part of the encoding. Nodes with an entry use the entry key directly.
	 */
	private ABlob cachedPrefix;

	@SuppressWarnings({ "rawtypes", "unchecked" })
	protected Index(long depth, MapEntry<K, V> entry, Ref<Index>[] entries,
			short mask, long count) {
		super(count);
		if (depth<UNRESOLVED_DEPTH||depth>MAX_DEPTH) throw new IllegalArgumentException("Index depth out of range: "+depth);
		if (depth==UNRESOLVED_DEPTH&&(count!=1||entry==null)) throw new IllegalArgumentException("Unresolved depth requires a single-entry node");
		this.depth = (int)depth;
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
		result.cachedPrefix=cachedPrefix; // ref update preserves cell values, so prefix is unchanged
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
		if (key==null) return null;
		long kl = key.hexLength();
		Index<K,V> node=this;
		while (true) {
			long pl = node.getDepth();
			if (kl < pl) return null; // key is too short to start with current prefix

			if (kl == pl) {
				if (entryKeyMatch(key,node.entry)) return node.entry;
				if (pl<MAX_DEPTH) return null;
			}

			// At maximum depth all longer keys sharing the first 255 bytes alias.
			if (pl==MAX_DEPTH) {
				return entryKeyMatch(key,node.entry) ? node.entry : null;
			}

			int digit = key.getHexDigit(pl);
			Index<K,V> child=node.getChild(digit);
			if (child == null) return null;
			if (child.getDepth() <= pl) return null; // malformed: descent must make progress
			node=child;
		}
	}

	/**
	 * Gets the child for a specific digit, or null if not found.
	 *
	 * Returns null for a non-Index child, which is possible in malformed
	 * encodings: child refs are lazy so the type cannot be checked at decode time.
	 *
	 * @param digit
	 * @return
	 */
	@SuppressWarnings("unchecked")
	private Index<K, V> getChild(int digit) {
		int i = Bits.indexForDigit(digit, mask);
		if (i < 0) return null;
		ACell c = children[i].getValue();
		if (!(c instanceof Index)) return null;
		return (Index<K, V>) c;
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
		if (getDepth()>MAX_RECURSIVE_DEPTH) return dissocDeep(k);
		if (count <= 1) {
			if (count == 0) return this; // Must already be empty singleton
			if (entryKeyMatch(k,entry)) {
				return empty();
			}
			return this; // leave existing entry in place
		}
		long pDepth = getDepth(); // hex depth of this node including prefix
		long kl = effectiveLength(k); // hex length of key to dissoc
		if (kl < pDepth) {
			// no match for sure, so no change
			return this;
		}
		if (kl == pDepth) {
			// need to check for match with current entry
			if (!entryKeyMatch(k,entry)) return this;
			// at this point have matched entry exactly. So need to remove it safely while
			// preserving invariants
			if (children.length == 1) {
				Index<K, V> c = (Index<K, V>) children[0].getValue();
				return c;
			} else {
				// Clearing current entry, keeping existing children (must be 2+)
				return new Index(getDepth(), null, children, mask, count - 1);
			}
		}
		// dissoc beyond current prefix length, so need to check children
		int digit = k.getHexDigit(pDepth);
		Index<K, V> oldChild = getChild(digit);
		if (oldChild == null) return this; // key miss (or malformed non-Index child)
		if (oldChild.getDepth() <= pDepth) return this; // malformed: child depth must increase, bounds recursion
		Index<K, V> newChild = oldChild.dissoc(k);
		Index<K,V> r=this.withChild(digit, oldChild, newChild);
		
		return r;
	}

	/** Extended-depth dissociation without a key-controlled Java call stack. */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private Index<K,V> dissocDeep(K k) {
		Index<K,V>[] parents=(Index<K,V>[])new Index[MAX_DEPTH+1];
		Index<K,V>[] oldChildren=(Index<K,V>[])new Index[MAX_DEPTH+1];
		byte[] childDigits=new byte[MAX_DEPTH+1];
		int pathLength=0;
		long kl=effectiveLength(k);
		Index<K,V> node=this;
		Index<K,V> result;

		while (true) {
			if (node.count<=1) {
				result=(node.count==1&&entryKeyMatch(k,node.entry)) ? empty() : node;
				break;
			}
			long pDepth=node.getDepth();
			if (kl<pDepth) {
				result=node;
				break;
			}
			if (kl==pDepth) {
				if (!entryKeyMatch(k,node.entry)) {
					result=node;
				} else if (node.children.length==1) {
					result=(Index<K,V>)node.children[0].getValue();
				} else {
					result=new Index(node.getDepth(),null,node.children,node.mask,node.count-1);
				}
				break;
			}

			int childDigit=k.getHexDigit(pDepth);
			Index<K,V> oldChild=node.getChild(childDigit);
			if (oldChild==null||oldChild.getDepth()<=pDepth) {
				result=node;
				break;
			}
			parents[pathLength]=node;
			oldChildren[pathLength]=oldChild;
			childDigits[pathLength]=(byte)childDigit;
			pathLength++;
			node=oldChild;
		}

		for (int i=pathLength-1;i>=0;i--) {
			Index<K,V> parent=parents[i];
			result=parent.withChild(childDigits[i]&0xFF,oldChildren[i],result);
		}
		return result;
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
	 * Tests if a key matches an entry's key, tolerating malformed entries.
	 *
	 * Decoded entry keys cannot be type-checked (entry refs are lazy), and generic
	 * erasure means any access via the K-typed getter would throw ClassCastException
	 * on a non-blob-like key. This helper checks the type on an untyped reference
	 * first, returning false (a deterministic miss) for malformed entries.
	 *
	 * @param key Key to look for (never null, correctly typed)
	 * @param me Entry to check (may be null, key may be any cell type)
	 * @return True if the entry is present with a matching blob-like key
	 */
	private static boolean entryKeyMatch(ABlobLike<?> key, MapEntry<?,?> me) {
		if (me==null) return false;
		ACell k=me.getKey();
		if (!(k instanceof ABlobLike)) return false; // malformed entry key
		return keyMatch(key,(ABlobLike<?>)k);
	}

	/**
	 * Common Prefix blob, must contain hex digits in range [0,depth).
	 *
	 * May contain more hex digits, this is irrelevant from the
	 * perspective of serialisation.
	 *
	 * Typically we populate with the key of the first entry added to avoid
	 * unnecessary blob instances being created. For entry-less nodes the result
	 * is cached, so repeated calls (e.g. during assoc descent) are O(1).
	 *
	 * Iterative rather than recursive, and returns Blob.EMPTY on malformed
	 * structure (non-Index child, missing entries): stack depth and hex digit
	 * reads must not depend on untrusted encodings.
	 */
	@SuppressWarnings("unchecked")
	private ABlob getPrefix() {
		if (entry!=null) return keyBlob(entry);
		ABlob result=cachedPrefix;
		if (result!=null) return result;
		// walk to the first descendant entry, remembering entry-less nodes passed
		ArrayList<Index<K,V>> path=new ArrayList<>();
		Index<K,V> node=this;
		while (true) {
			if (node.entry!=null) { result=keyBlob(node.entry); break; }
			result=node.cachedPrefix;
			if (result!=null) break;
			if (node.children.length==0) { result=Blob.EMPTY; break; } // only valid for EMPTY
			path.add(node);
			ACell c=node.children[0].getValue();
			if (!(c instanceof Index)) { result=Blob.EMPTY; break; } // malformed child
			node=(Index<K,V>)c;
		}
		// benign race: all threads compute the same immutable result
		for (Index<K,V> n : path) n.cachedPrefix=result;
		return result;
	}

	/**
	 * Gets an entry's key as a Blob, or Blob.EMPTY if the key is not blob-like
	 * (possible in malformed encodings, since entry refs are lazy).
	 */
	private static ABlob keyBlob(MapEntry<?,?> me) {
		ACell k=me.getKey();
		if (!(k instanceof ABlobLike)) return Blob.EMPTY;
		return ((ABlobLike<?>)k).toBlob();
	}

	@Override
	protected void accumulateEntries(Collection<Entry<K, V>> h) {
		if (getDepth()>MAX_RECURSIVE_DEPTH) {
			for (long i=0;i<count;i++) h.add(entryAt(i));
			return;
		}
		for (int i = 0; i < children.length; i++) {
			children[i].getValue().accumulateEntries(h);
		}
		if (entry != null) h.add(entry);
	}

	@Override
	protected void accumulateKeySet(Set<K> h) {
		if (getDepth()>MAX_RECURSIVE_DEPTH) {
			for (long i=0;i<count;i++) h.add(entryAt(i).getKey());
			return;
		}
		for (int i = 0; i < children.length; i++) {
			children[i].getValue().accumulateKeySet(h);
		}
		if (entry != null) h.add(entry.getKey());
	}

	@Override
	protected void accumulateValues(java.util.List<V> al) {
		if (getDepth()>MAX_RECURSIVE_DEPTH) {
			for (long i=0;i<count;i++) al.add(entryAt(i).getValue());
			return;
		}
		// add this entry first, since we want lexicographic order
		if (entry != null) al.add(entry.getValue());
		for (int i = 0; i < children.length; i++) {
			children[i].getValue().accumulateValues(al);
		}
	}

	@Override
	public void forEach(BiConsumer<? super K, ? super V> action) {
		if (getDepth()>MAX_RECURSIVE_DEPTH) {
			for (long i=0;i<count;i++) {
				MapEntry<K,V> me=entryAt(i);
				action.accept(me.getKey(),me.getValue());
			}
			return;
		}
		if (entry != null) action.accept(entry.getKey(), entry.getValue());
		for (int i = 0; i < children.length; i++) {
			children[i].getValue().forEach(action);
		}
	}

	@Override
	public Index<K, V> assocEntry(MapEntry<K, V> e) {
		return assocEntry(e,0);
	}
	
	/**
	 * Associates an entry, assuming the first {@code match} hex digits of the new key
	 * are already known to match all keys in this subtree (i.e. this node's prefix).
	 *
	 * The caller establishes this invariant structurally: descent into a child at
	 * digit position d implies the key matched the parent prefix up to depth d. This
	 * makes prefix matching incremental — each digit of the key is compared at most
	 * once over the whole descent, rather than re-matched from position 0 at every level.
	 *
	 * Robustness: encodings are not deep-validated on receipt, so a malformed Index
	 * (declared depth longer than the physical prefix, non-increasing child depths,
	 * non-blob keys, non-Index children) may reach this code. All hex digit reads are
	 * clamped to physical blob lengths, and detected impossibilities return null
	 * (deterministically), mirroring the invalid-key-type case. Results on malformed
	 * input are unspecified but deterministic, with no out-of-bounds reads.
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private Index<K, V> assocEntry(MapEntry<K, V> e, long match) {
		// Preserve the original allocation-free recursive path for all historical
		// Index depths. Extended-depth tries use an explicit stack below.
		if (getDepth()>MAX_RECURSIVE_DEPTH) return assocEntryDeep(e,match);

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

		long newKeyLength = effectiveLength(k); // hex length of new key, up to MAX_DEPTH
		ABlobLike prefix=getPrefix(); // prefix of current node (should be valid up to depth)
		long plen = effectiveLength(prefix);
		// Defensive clamps for malformed encodings: never read hex digits beyond the
		// physical length of either blob. For valid structures plen >= depth >= match,
		// so pDepth == depth and the clamps are no-ops.
		long pDepth = Math.min(getDepth(), plen);
		if (match > pDepth) match = pDepth;
		if (match > newKeyLength) match = newKeyLength;
		long mkl; // matched key length
		if (newKeyLength >= pDepth) {
			// constrain relevant key length by match with current prefix
			mkl = match + k.hexMatch(prefix, match, pDepth-match);
		} else {
			mkl = match + k.hexMatch(prefix, match, newKeyLength - match);
		}
		if (mkl < getDepth()) {
			// we collide at a point shorter than the current prefix length
			if (mkl >= plen) return null; // malformed: prefix physically shorter than declared depth
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
		// past the collision branch we must have mkl == depth == pDepth, hence:
		assert (newKeyLength >= getDepth());
		if (newKeyLength == getDepth()) {
			// we must have matched the current entry exactly
			if (entry == null) {
				// just add entry at this position
				return new Index<K, V>(getDepth(), e, (Ref[]) children, mask, count + 1);
			}
			if (entry == e) return this;

			// swap entry, no need to change count
			return new Index<K, V>(getDepth(), e, (Ref[]) children, mask, count);
		}
		// at this point we have matched full prefix, but new key length is longer.
		// so we need to update (or add) exactly one child
		int childDigit = k.getHexDigit(getDepth());
		Index<K, V> oldChild = getChild(childDigit);
		Index<K, V> newChild;
		if (oldChild == null) {
			if (Bits.indexForDigit(childDigit, mask) >= 0) return null; // malformed: child ref is not an Index
			newChild = create(e); // Must be at least 1 beyond current prefix. Safe because pDepth < MAX_DEPTH
		} else {
			if (oldChild.getDepth() <= getDepth()) return null; // malformed: child depth must increase, bounds recursion
			// digits [0,depth) matched this node's prefix, so also match all child keys
			newChild = oldChild.assocEntry(e, getDepth());
			if (newChild == null) return null; // malformed structure detected in child
		}
		return withChild(childDigit, oldChild, newChild);
	}

	/**
	 * Extended-depth association. This is deliberately a cold path: the arrays are
	 * allocated only after descent passes the historical 32-byte Index limit.
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private Index<K,V> assocEntryDeep(MapEntry<K,V> e, long match) {
		ACell maybeValidKey=e.getKey();
		if (!(maybeValidKey instanceof ABlobLike)) return null;
		ABlobLike<?> k=(ABlobLike)maybeValidKey;
		long newKeyLength=effectiveLength(k);

		Index<K,V>[] parents=(Index<K,V>[])new Index[MAX_DEPTH+1];
		Index<K,V>[] oldChildren=(Index<K,V>[])new Index[MAX_DEPTH+1];
		byte[] childDigits=new byte[MAX_DEPTH+1];
		int pathLength=0;
		Index<K,V> node=this;
		Index<K,V> result;

		while (true) {
			if (node.count==0L) {
				result=create(e);
				break;
			}
			if (node.count==1L) {
				assert node.mask==(short)0;
				if (node.entry.keyEquals(e)) {
					result=(node.entry==e) ? node : create(e);
					break;
				}
			}

			ABlobLike<?> prefix=node.getPrefix();
			long plen=effectiveLength(prefix);
			long pDepth=Math.min(node.getDepth(),plen);
			if (match>pDepth) match=pDepth;
			if (match>newKeyLength) match=newKeyLength;
			long mkl=match+k.hexMatch(prefix,match,Math.min(newKeyLength,pDepth)-match);

			if (mkl<node.getDepth()) {
				if (mkl>=plen) return null;
				if (mkl==newKeyLength) {
					int splitDigit=prefix.getHexDigit(mkl);
					short splitMask=(short)(1<<splitDigit);
					result=new Index<K,V>(mkl,e,new Ref[] { node.getRef() },splitMask,node.count+1);
				} else {
					Index<K,V> branch1=node;
					Index<K,V> branch2=create(e);
					int d1=prefix.getHexDigit(mkl);
					int d2=k.getHexDigit(mkl);
					if (d1>d2) {
						Index<K,V> temp=branch1;
						branch1=branch2;
						branch2=temp;
					}
					Ref[] newChildren=new Ref[] { branch1.getRef(),branch2.getRef() };
					short newMask=(short)((1<<d1)|(1<<d2));
					result=new Index<K,V>(mkl,null,newChildren,newMask,node.count+1L);
				}
				break;
			}

			assert newKeyLength>=node.getDepth();
			if (newKeyLength==node.getDepth()) {
				if (node.entry==null) {
					result=new Index<K,V>(node.getDepth(),e,(Ref[])node.children,node.mask,node.count+1);
				} else if (node.entry==e) {
					result=node;
				} else {
					result=new Index<K,V>(node.getDepth(),e,(Ref[])node.children,node.mask,node.count);
				}
				break;
			}

			int childDigit=k.getHexDigit(node.getDepth());
			Index<K,V> oldChild=node.getChild(childDigit);
			if (oldChild==null) {
				if (Bits.indexForDigit(childDigit,node.mask)>=0) return null;
				result=node.withChild(childDigit,null,create(e));
				break;
			}
			if (oldChild.getDepth()<=node.getDepth()) return null;
			parents[pathLength]=node;
			oldChildren[pathLength]=oldChild;
			childDigits[pathLength]=(byte)childDigit;
			pathLength++;
			match=node.getDepth();
			node=oldChild;
		}

		for (int i=pathLength-1;i>=0;i--) {
			Index<K,V> parent=parents[i];
			result=parent.withChild(childDigits[i]&0xFF,oldChildren[i],result);
		}
		return result;
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
			return new Index<K, V>(getDepth(), entry, newChildren, newMask,
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
				return new Index<K, V>(getDepth(), entry, newChildren, newMask,
						count - oldChild.count());
			} else {
				// need to replace a child
				int childPos = Bits.positionForDigit(childDigit, mask);
				newChildren = children.clone();
				newChildren[childPos] = newChild.getRef();
				long newCount = count + newChild.count() - oldChild.count();
				return new Index<K, V>(getDepth(), entry, newChildren, mask, newCount);
			}
		}
	}

	@Override
	public <R> R reduceValues(BiFunction<? super R, ? super V, ? extends R> func, R initial) {
		if (getDepth()>MAX_RECURSIVE_DEPTH) {
			for (long i=0;i<count;i++) initial=func.apply(initial,entryAt(i).getValue());
			return initial;
		}
		if (entry != null) initial = func.apply(initial, entry.getValue());
		int n = children.length;
		for (int i = 0; i < n; i++) {
			initial = children[i].getValue().reduceValues(func, initial);
		}
		return initial;
	}

	@Override
	public <R> R reduceEntries(BiFunction<? super R, MapEntry<K, V>, ? extends R> func, R initial) {
		if (getDepth()>MAX_RECURSIVE_DEPTH) {
			for (long i=0;i<count;i++) initial=func.apply(initial,entryAt(i));
			return initial;
		}
		if (entry != null) initial = func.apply(initial, entry);
		int n = children.length;
		for (int i = 0; i < n; i++) {
			initial = children[i].getValue().reduceEntries(func, initial);
		}
		return initial;
	}
	
	@Override
	public Index<K, V> filterValues(Predicate<V> pred) {
		if (getDepth()>MAX_RECURSIVE_DEPTH) {
			Index<K,V> result=this;
			for (long i=0;i<count;i++) {
				MapEntry<K,V> me=entryAt(i);
				if (!pred.test(me.getValue())) result=result.dissoc(me.getKey());
			}
			return result;
		}
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

		// We only have a meaningful depth if more than one entry. Preserve the
		// historical one-byte fast path; extended depths use canonical VLQ.
		if (getDepth() < 128) {
			bs[pos++] = (byte)getDepth();
		} else {
			pos = Format.writeVLQCount(bs,pos,getDepth());
		}
		
		// write mask
		pos = Utils.writeShort(bs,pos,mask);

		// finally write children
		int n = children.length;
		for (int i = 0; i < n; i++) {
			pos = encodeChild(bs,pos,i);
		}
		return pos;
	}
	
	@Override
	public int calcHeaderLength() {
		// tag plus VLQ count; multiple entries add the entry marker, VLQ depth and mask
		int hl=1+Format.getVLQCountLength(count);
		if (count>1) hl+=1+Format.getVLQCountLength(depth)+2;
		return hl;
	}

	@Override
	public int getEncodingLength() {
		if (encoding!=null) return encoding.size();

		int el=calcHeaderLength();
		if (entry!=null) {
			el+=entry.getKeyRef().getEncodingLength();
			el+=entry.getValueRef().getEncodingLength();
		}
		for (Ref<Index<K, V>> cref : children) {
			el+=cref.getEncodingLength();
		}
		return el;
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
	
	@Override
	protected MapEntry<K, V> getEntryByHash(Hash hash) {
		throw new UnsupportedOperationException();
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
		int d=depth;
		if (d!=UNRESOLVED_DEPTH) return d;
		// Decoded single-entry node: the depth is the entry key's hex length
		ACell k=(entry==null)?null:entry.getKey();
		d=(k instanceof ABlobLike<?> key)?(int)effectiveLength(key):0;
		depth=d;
		return d;
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
			ACell ek=entry.getKey();
			if (!(ek instanceof ABlobLike)) throw new InvalidDataException("Entry key not blob-like: "+Utils.getClassName(ek),this);
			long entryKeyLength=((ABlobLike<?>)ek).hexLength();
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
	public void validate() throws InvalidDataException {
		super.validate();
		
		if ((getDepth()<0)||(getDepth()>MAX_DEPTH)) throw new InvalidDataException("Invalid index depth",this);
		
		if (entry!=null) {
			ABlobLike<K> k=RT.ensureBlobLike(entry.getKey());
			if (k==null) throw new InvalidDataException("Invalid entry key type: "+Utils.getClassName(entry.getKey()),this);
			if (getDepth()!=effectiveLength(k)) throw new InvalidDataException("Entry at inconsistent depth",this);
		}
		
		ABlobLike<?> prefix=getPrefix();
		if (getDepth()>effectiveLength(prefix)) throw new InvalidDataException("depth longer than common prefix",this);

		long ecount = (entry == null) ? 0 : 1;
		int n = children.length;
		for (int i = 0; i < n; i++) {
			ACell o = children[i].getValue();
			if (!(o instanceof Index))
				throw new InvalidDataException("Illegal Index child type: " + Utils.getClass(o), this);
			Index<K, V> c = (Index<K, V>) o;
			
			long ccount=c.count();
			if (ccount==0) {
				throw new InvalidDataException("Child "+i+" should not be empty! At depth "+getDepth(),this);
			}
			
			if (c.getDepth() <= getDepth()) {
				throw new InvalidDataException("Child must have greater depth than parent", this);
			}
			
			ABlobLike<?> childPrefix=c.getPrefix();
			long ml=prefix.hexMatch(childPrefix, 0, getDepth());
			if (ml<getDepth()) throw new InvalidDataException("Child does not have matching common prefix", this);
			
			// check child has correct digit for mask position
			int digit=childPrefix.getHexDigit(getDepth());
			if (i!=Bits.indexForDigit(digit, mask)) throw new InvalidDataException("Child does not have correct digit", this);

			ecount += ccount;
		}

		if (count != ecount) throw new InvalidDataException("Bad entry count: " + ecount + " expected: " + count, this);
	}


	@SuppressWarnings("unchecked")
	@Override
	public Index<K, V> empty() {
		return (Index<K, V>) EMPTY;
	}
	
	/**
	 * Return an empty Index (with no elements)
	 * @param <K> Type of keys
	 * @param <V> Type of Values
	 * @return Empty Index instance (singleton)
	 */
	@SuppressWarnings("unchecked")
	public static final <K extends ABlobLike<?>, V extends ACell> Index<K, V> none() {
		return (Index<K, V>) EMPTY;
	}

	@Override
	public MapEntry<K, V> entryAt(long ix) {
		Index<K,V> node=this;
		while (true) {
			if (node.entry != null) {
				if (ix == 0L) return node.entry;
				ix--;
			}
			Index<K,V> next=null;
			for (int i = 0; i < node.children.length; i++) {
				ACell cell = node.children[i].getValue();
				if (!(cell instanceof Index)) continue; // malformed child, treat as empty
				@SuppressWarnings("unchecked")
				Index<K,V> child=(Index<K,V>)cell;
				long cc=child.count();
				if (ix < cc) {
					next=child;
					break;
				}
				ix-=cc;
			}
			if (next==null) throw new IndexOutOfBoundsException((int)ix);
			node=next;
		}
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
		if (getDepth()>MAX_RECURSIVE_DEPTH) {
			for (long i=0;i<count;i++) {
				if (Cells.equals(value,entryAt(i).getValue())) return true;
			}
			return false;
		}
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

	@Override
	public Index<K, V> mergeDifferences(AMap<K, V> b, MergeFunction<V> func) {
		if (b instanceof Index) {
			return mergeDifferences((Index<K, V>)b,func);
		} else {
			throw new UnsupportedOperationException("Cannot merge maps for different type");
		}
	}
	
	/**
	 * Merge two indexes with a merge function.
	 *
	 * For every key present in either index, applies {@code func} as for
	 * {@link AMap#mergeDifferences(AMap, MergeFunction)}: a key present on only one side is passed
	 * with a {@code null} counterpart; a key present in both with equal values is kept unchanged
	 * without calling {@code func}.
	 *
	 * <p>Identity contract (see {@link AMap#mergeDifferences}): returns {@code this} (the exact
	 * same object, no allocation) whenever the merged result equals {@code this}. The result is
	 * built bottom-up so that any unchanged sub-trie — and hence the whole node — is returned by
	 * reference identity, not rebuilt. This is verified by the {@code assertSame} cases in
	 * {@code IndexMergeTest}.</p>
	 *
	 * <p>This is a structural radix merge: identical (or value-equal, detected via {@code Ref}
	 * equality) sub-tries are skipped in O(1) and shared, so the cost and allocation are
	 * proportional to the divergence between the two indexes, not their total size.</p>
	 *
	 * @param b Other index to merge with
	 * @param func Merge function for Index values
	 * @return Merged Index, or {@code this} by reference identity if the result is unchanged
	 */
	public Index<K, V> mergeDifferences(Index<K, V> b, MergeFunction<V> func) {
		return mergeNode(this, b, func);
	}

	/**
	 * Gets the child Ref for a specific digit, or null if not found.
	 */
	private Ref<Index<K, V>> getChildRef(int digit) {
		int i = Bits.indexForDigit(digit, mask);
		if (i < 0) return null;
		return children[i];
	}

	/**
	 * Structural recursive merge of two Index nodes. See {@link #mergeDifferences(Index, MergeFunction)}.
	 */
	static <K extends ABlobLike<?>, V extends ACell> Index<K, V> mergeNode(Index<K, V> a, Index<K, V> b, MergeFunction<V> func) {
		if (a == b) return a;                                // shared structure: O(1), no alloc, no hashing
		Hash ah=a.cachedHash();
		Hash bh=b.cachedHash();
		if (ah!=null&&bh!=null&&ah.equals(bh)) return a;      // equal known structure: no child resolution
		if (a.getDepth()>MAX_RECURSIVE_DEPTH||b.getDepth()>MAX_RECURSIVE_DEPTH) return mergeDeep(a,b,func);
		if (a.count == 0) return applySide(b, func, false);  // a empty: all of b is right-side
		if (b.count == 0) return applySide(a, func, true);   // b empty: all of a is left-side

		long da = a.getDepth(), db = b.getDepth();
		long pmin = Math.min(da, db);
		if (pmin > 0) {
			ABlob pa = a.getPrefix(), pb = b.getPrefix();
			// clamp digit reads to physical prefix lengths (untrusted encodings may
			// declare depths longer than the actual prefix)
			long pm = Math.min(pmin, Math.min(pa.hexLength(), pb.hexLength()));
			long m = pa.hexMatch(pb, 0, pm);
			if (m < pmin) {
				if (m >= pm) throw new IllegalArgumentException("Malformed Index: declared depth exceeds physical prefix");
				// prefixes diverge at digit m: disjoint keyspaces
				return branchDisjoint(m, pa.getHexDigit(m), a, pb.getHexDigit(m), b, func);
			}
		}
		// common prefix matches down to the shallower depth: aligned or nested
		if (da == db) return mergeAligned(a, b, func);
		return (da < db) ? mergeNested(a, b, func, true)
		                 : mergeNested(b, a, func, false);
	}

	/** Merge two nodes that share the same prefix and depth. */
	private static <K extends ABlobLike<?>, V extends ACell> Index<K, V> mergeAligned(Index<K, V> a, Index<K, V> b, MergeFunction<V> func) {
		MapEntry<K, V> ne = mergeEntries(a.entry, b.entry, func);
		Index<K, V>[] kids = null; // built lazily on first child change
		int um = (a.mask | b.mask) & 0xFFFF;
		for (int d = 0; d < 16; d++) {
			if (((um >> d) & 1) == 0) continue;
			Ref<Index<K, V>> aref = a.getChildRef(d);
			Ref<Index<K, V>> bref = b.getChildRef(d);
			Index<K, V> ac = null;
			Index<K, V> nc;
			if (aref == null) {
				nc = applySide(bref.getValue(), func, false);      // b-only child
			} else if (bref == null) {
				ac = aref.getValue();
				nc = applySide(ac, func, true);                    // a-only child
			} else if (aref.equals(bref)) {
				continue;                                           // identical sub-trie: skip without resolving
			} else {
				ac = aref.getValue();
				nc = mergeNode(ac, bref.getValue(), func);
			}
			if (nc != null && nc.count == 0) nc = null;            // empty child => absent
			if (nc != ac) {
				if (kids == null) kids = collectKids(a);
				kids[d] = nc;
			}
		}
		if (kids == null) {
			if (ne == a.entry) return a;                          // nothing changed
			kids = collectKids(a);
		}
		return rebuild(a.getDepth(), ne, kids);
	}

	/**
	 * Merge a shallower node with a deeper node whose keys nest entirely under one of the
	 * shallower node's child digits. {@code shallowIsLeft} preserves the (left,right) arg order
	 * into {@code func} when the deeper node is the left ('own') argument.
	 */
	private static <K extends ABlobLike<?>, V extends ACell> Index<K, V> mergeNested(Index<K, V> shallow, Index<K, V> deep, MergeFunction<V> func, boolean shallowIsLeft) {
		ABlob deepPrefix = deep.getPrefix();
		if (shallow.getDepth() >= deepPrefix.hexLength()) throw new IllegalArgumentException("Malformed Index: declared depth exceeds physical prefix");
		int dig = deepPrefix.getHexDigit(shallow.getDepth());
		MapEntry<K, V> ne = singleEntry(shallow.entry, func, shallowIsLeft); // shallow entry is single-side
		Index<K, V>[] kids = null; // built lazily on first change
		boolean changed = (ne != shallow.entry);
		int sm = shallow.mask & 0xFFFF;
		for (int d = 0; d < 16; d++) {
			if (d == dig) continue;
			if (((sm >> d) & 1) == 0) continue;
			Index<K, V> sc = shallow.getChild(d);
			Index<K, V> nc = applySide(sc, func, shallowIsLeft);
			if (nc.count == 0) nc = null;
			if (nc != sc) {
				if (kids == null) kids = collectKids(shallow);
				kids[d] = nc;
				changed = true;
			}
		}
		Index<K, V> scDig = shallow.getChild(dig); // may be null
		Index<K, V> merged;
		if (scDig == null) {
			merged = applySide(deep, func, !shallowIsLeft);
		} else {
			merged = shallowIsLeft ? mergeNode(scDig, deep, func) : mergeNode(deep, scDig, func);
		}
		if (merged != null && merged.count == 0) merged = null;
		if (merged != scDig) {
			if (kids == null) kids = collectKids(shallow);
			kids[dig] = merged;
			changed = true;
		}
		if (!changed) return shallow; // no-op: deep added nothing, result equals shallow (no allocation)
		if (kids == null) kids = collectKids(shallow);
		return rebuild(shallow.getDepth(), ne, kids);
	}

	/** Build a branch node at depth {@code m} for two disjoint sub-tries (digA != digB). */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static <K extends ABlobLike<?>, V extends ACell> Index<K, V> branchDisjoint(long m, int digA, Index<K, V> a, int digB, Index<K, V> b, MergeFunction<V> func) {
		Index<K, V> A = applySide(a, func, true);
		Index<K, V> B = applySide(b, func, false);
		if (A.count == 0) return B; // if both empty, B is the empty singleton
		if (B.count == 0) return A;
		int mask = (1 << digA) | (1 << digB);
		Ref[] refs = (digA < digB) ? new Ref[] { A.getRef(), B.getRef() }
		                           : new Ref[] { B.getRef(), A.getRef() };
		return new Index<K, V>(m, null, refs, (short) mask, A.count + B.count);
	}

	/** Apply {@code func} to every entry of a node as a single side (other side null). Returns the same node if unchanged. */
	private static <K extends ABlobLike<?>, V extends ACell> Index<K, V> applySide(Index<K, V> node, MergeFunction<V> func, boolean left) {
		if (node.count == 0) return node;
		if (node.getDepth()>MAX_RECURSIVE_DEPTH) {
			Index<K,V> result=node;
			for (long i=0;i<node.count;i++) {
				MapEntry<K,V> me=node.entryAt(i);
				V oldValue=me.getValue();
				V newValue=left ? func.merge(me.getKey(),oldValue,null)
						: func.merge(me.getKey(),null,oldValue);
				if (newValue==null) {
					result=result.dissoc(me.getKey());
				} else if (!Utils.equals(oldValue,newValue)) {
					result=result.assoc(me.getKey(),newValue);
				}
			}
			return result;
		}
		MapEntry<K, V> ne = singleEntry(node.entry, func, left);
		Index<K, V>[] kids = null;
		int m = node.mask & 0xFFFF;
		for (int d = 0; d < 16; d++) {
			if (((m >> d) & 1) == 0) continue;
			Index<K, V> c = node.getChild(d);
			Index<K, V> nc = applySide(c, func, left);
			if (nc.count == 0) nc = null;
			if (nc != c) {
				if (kids == null) kids = collectKids(node);
				kids[d] = nc;
			}
		}
		if (kids == null) {
			if (ne == node.entry) return node;
			kids = collectKids(node);
		}
		return rebuild(node.getDepth(), ne, kids);
	}

	/**
	 * Stack-safe ordered merge for extended-depth subtries. The structural merge
	 * remains the fast path; this bounded fallback is reached only beyond the old
	 * 32-byte key limit.
	 */
	private static <K extends ABlobLike<?>, V extends ACell> Index<K,V> mergeDeep(
			Index<K,V> a, Index<K,V> b, MergeFunction<V> func) {
		Index<K,V> result=a;
		long ai=0;
		long bi=0;
		while (ai<a.count||bi<b.count) {
			MapEntry<K,V> ae=(ai<a.count) ? a.entryAt(ai) : null;
			MapEntry<K,V> be=(bi<b.count) ? b.entryAt(bi) : null;
			if (ae!=null&&be!=null&&keyMatch(ae.getKey(),be.getKey())) {
				V av=ae.getValue();
				V bv=be.getValue();
				if (!Utils.equals(av,bv)) {
					V nv=func.merge(ae.getKey(),av,bv);
					if (nv==null) result=result.dissoc(ae.getKey());
					else if (!Utils.equals(av,nv)) result=result.assoc(ae.getKey(),nv);
				}
				ai++;
				bi++;
				continue;
			}

			boolean takeA=(be==null)||(ae!=null&&ae.getKey().compareTo(be.getKey().toBlob())<0);
			MapEntry<K,V> me=takeA ? ae : be;
			V oldValue=me.getValue();
			V nv=takeA ? func.merge(me.getKey(),oldValue,null)
					: func.merge(me.getKey(),null,oldValue);
			if (takeA) {
				if (nv==null) result=result.dissoc(me.getKey());
				else if (!Utils.equals(oldValue,nv)) result=result.assoc(me.getKey(),nv);
				ai++;
			} else {
				if (nv!=null) result=result.assoc(me.getKey(),nv);
				bi++;
			}
		}
		return result;
	}

	/** Snapshot a node's children into a digit-indexed array of size 16 (absent digits are null). */
	@SuppressWarnings("unchecked")
	private static <K extends ABlobLike<?>, V extends ACell> Index<K, V>[] collectKids(Index<K, V> node) {
		Index<K, V>[] kids = (Index<K, V>[]) new Index[16];
		int m = node.mask & 0xFFFF;
		for (int d = 0; d < 16; d++) {
			if (((m >> d) & 1) != 0) kids[d] = node.getChild(d);
		}
		return kids;
	}

	/**
	 * Construct a canonical Index node from a depth, optional entry and a digit-indexed child array.
	 * Handles the canonicalisation invariants (empty, single-entry, entry-less single-child promotion).
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static <K extends ABlobLike<?>, V extends ACell> Index<K, V> rebuild(long depth, MapEntry<K, V> entry, Index<K, V>[] kids) {
		int mask = 0, nk = 0, lastDigit = -1;
		long count = (entry == null) ? 0 : 1;
		for (int d = 0; d < 16; d++) {
			Index<K, V> c = kids[d];
			if (c == null || c.count == 0) continue;
			mask |= (1 << d);
			nk++;
			lastDigit = d;
			count += c.count;
		}
		if (nk == 0) {
			if (entry == null) return (Index<K, V>) EMPTY;
			return new Index<K, V>(depth, entry, EMPTY_CHILDREN, (short) 0, 1);
		}
		if (entry == null && nk == 1) return kids[lastDigit]; // promote single child (depth > this depth)
		Ref[] refs = new Ref[nk];
		int i = 0;
		for (int d = 0; d < 16; d++) {
			Index<K, V> c = kids[d];
			if (c == null || c.count == 0) continue;
			refs[i++] = c.getRef();
		}
		return new Index<K, V>(depth, entry, refs, (short) mask, count);
	}

	/** Merge two entries at the same key (either may be null). Returns the surviving entry, or null if removed. */
	private static <K extends ABlobLike<?>, V extends ACell> MapEntry<K, V> mergeEntries(MapEntry<K, V> ea, MapEntry<K, V> eb, MergeFunction<V> func) {
		if (eb == null) return singleEntry(ea, func, true);
		if (ea == null) return singleEntry(eb, func, false);
		V va = ea.getValue(), vb = eb.getValue();
		if (Utils.equals(va, vb)) return ea;                  // equal values: keep a's, no func call
		V nv = func.merge(ea.getKey(), va, vb);
		if (nv == null) return null;
		if (Utils.equals(va, nv)) return ea;
		return ea.withValue(nv);
	}

	/** Apply {@code func} to a single-side entry (other side null). Returns the entry, or null if removed. */
	private static <K extends ABlobLike<?>, V extends ACell> MapEntry<K, V> singleEntry(MapEntry<K, V> e, MergeFunction<V> func, boolean left) {
		if (e == null) return null;
		V v = e.getValue();
		V nv = left ? func.merge(e.getKey(), v, null) : func.merge(e.getKey(), null, v);
		if (nv == null) return null;
		if (Utils.equals(v, nv)) return e;
		return e.withValue(nv);
	}

	@Override
	public <R extends ACell> ADataStructure<R> map(Function<MapEntry<K, V>, R> mapper) {
		// Index result=EMPTY;
		// return result;
		throw new TODOException();
	}

	@Override
	public long seek(ABlobLike<?> key) {
		throw new TODOException();
	}

}
