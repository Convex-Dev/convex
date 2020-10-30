package convex.core.data;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Iterator;
import java.util.function.Function;

import convex.core.crypto.Hash;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;
import convex.core.util.Utils;

/**
 * Class implementing a persistent smart set.
 * 
 * Wraps a map, where keys in the map represent the presence of an element in
 * the set Map values must be non-null to allow efficient merge operations to
 * distinguish between present and non-present set values.
 *
 * @param <T> The type of set elements
 */
public class Set<T> extends ASet<T> {

	static final Set<?> EMPTY = new Set<>(Maps.empty());

	/**
	 * Internal map used to represent the set
	 */
	private final AHashMap<T, Object> map;

	private Set(AHashMap<T, Object> source) {
		map = source;
	}

	@SuppressWarnings("unchecked")
	static <T> Set<T> wrap(AHashMap<T, Object> source) {
		if (source.isEmpty()) return (Set<T>) EMPTY;
		return new Set<T>(source);
	}

	/**
	 * Create a set using all elements in the given sequence.
	 * 
	 * @param <T> Type of elements
	 * @param a   Any sequence of elements
	 * @return
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	static <T> Set<T> create(ASequence<T> a) {
		if (a.isEmpty()) return (Set<T>) EMPTY;

		// dirty, dirty hack because Java doesn't like mutating variables in enclosing
		// scope.
		AHashMap[] m = new AHashMap[] { Maps.empty() };

		// we use the visitor approach to optimise this, because we want to avoid
		// building new Refs for each element.
		a.visitElementRefs(r -> {
			MapEntry<T, Object> me = MapEntry.createRef(r, Ref.TRUE_VALUE);
			m[0] = m[0].assocEntry(me);
		});
		return Set.wrap(m[0]);
	}

	public static <T> Set<T> create(T[] elements) {
		AHashMap<T, Object> m = Maps.empty();
		for (T e : elements) {
			MapEntry<T, Object> me = MapEntry.createRef(Ref.get(e), Ref.TRUE_VALUE);
			m = m.assocEntry(me);
		}
		return Set.wrap(m);
	}

	@Override
	public int size() {
		return map.size();
	}

	@Override
	public boolean isEmpty() {
		return map.isEmpty();
	}

	@Override
	public boolean contains(Object o) {
		return map.containsKey(o);
	}

	@Override
	public Iterator<T> iterator() {
		return map.keySet().iterator();
	}

	@Override
	public Object[] toArray() {
		return map.keySet().toArray();
	}

	@Override
	public <V> V[] toArray(V[] a) {
		return map.keySet().toArray(a);
	}

	@Override
	public boolean containsAll(Collection<?> c) {
		return map.keySet().containsAll(c);
	}

	@Override
	public void ednString(StringBuilder sb) {
		sb.append("#{");
		int size = size();
		for (int i = 0; i < size; i++) {
			if (i > 0) sb.append(',');
			sb.append(Utils.ednString(map.entryAt(i).getKey()));
		}
		sb.append('}');
	}
	
	@Override
	public void print(StringBuilder sb) {
		sb.append("#{");
		int size = size();
		for (int i = 0; i < size; i++) {
			if (i > 0) sb.append(',');
			Utils.print(sb,map.entryAt(i).getKey());
		}
		sb.append('}');
	}

	@Override
	public int getRefCount() {
		return map.getRefCount();
	}

	@Override
	public <R> Ref<R> getRef(int i) {
		return map.getRef(i);
	}

	@Override
	public Object getByHash(Hash hash) {
		MapEntry<?, ?> me = map.getEntryByHash(hash);
		if (me == null) return null;
		return me.getKey();
	}

	@Override
	public Set<T> updateRefs(IRefFunction func) {
		AHashMap<T, Object> m = map.updateRefs(func);
		if (map == m) return this;
		return wrap(m);
	}

	@Override
	public boolean isCanonical() {
		return map.isCanonical();
	}

	@Override
	public int write(byte[] bs, int pos) {
		bs[pos++]=Tag.SET;
		return writeRaw(bs,pos);
	}

	@Override
	public int writeRaw(byte[] bs, int pos) {
		return map.write(bs,pos);
	}

	@SuppressWarnings("unchecked")
	public static <T> Set<T> read(ByteBuffer bb) throws BadFormatException {
		Object o = Format.read(bb);
		// we need to read the hashmap object directly, and validate it is indeed a
		// hashmap
		if (!(o instanceof AHashMap)) throw new BadFormatException("Map expected as set content");
		AHashMap<T, Object> map = (AHashMap<T, Object>) o;

		return wrap(map);
	}

	@Override
	public long count() {
		return map.count();
	}

	@Override
	public int estimatedEncodingSize() {
		return map.estimatedEncodingSize();
	}

	@Override
	public ASet<T> include(T a) {
		if (map.containsKey(a)) return this;
		return wrap(map.assocRef(Ref.get(a), true));
	}

	@Override
	public ASet<T> includeRef(Ref<T> ref) {
		if (map.containsKeyRef(ref)) return this;
		return wrap(map.assocRef(ref, false));
	}

	@Override
	public ASet<T> disj(T a) {
		return wrap(map.dissoc(a));
	}

	@Override
	public Set<T> conjAll(Collection<T> b) {
		if (b instanceof Set) return conjAll((Set<T>) b);
		ASequence<T> seq = RT.sequence(b);
		if (seq == null) throw new IllegalArgumentException("Can't convert to seq: " + Utils.getClassName(b));
		return conjAll(Set.create(RT.sequence(b)));
	}

	@Override
	public Set<T> disjAll(Collection<T> b) {
		if (b instanceof Set) return disjAll((Set<T>) b);
		ASequence<T> seq = RT.sequence(b);
		if (seq == null) throw new IllegalArgumentException("Can't convert to seq: " + Utils.getClassName(b));
		return disjAll(Set.create(seq));
	}

	public Set<T> conjAll(Set<T> b) {
		// any key in either map results in a non-null value, assuming one is non-null
		AHashMap<T, Object> rmap = map.mergeDifferences(b.map, (x, y) -> (y == null) ? x : y);
		if (map == rmap) return this;
		return wrap(rmap);
	}

	public Set<T> disjAll(Set<T> b) {
		// any value in y removes the value in x
		AHashMap<T, Object> rmap = map.mergeWith(b.map, (x, y) -> (y == null) ? x : null);
		if (map == rmap) return this;
		return wrap(rmap);
	}

	@SuppressWarnings("unchecked")
	@Override
	public AVector<T> toVector() {
		return (AVector<T>) RT.vec(Vectors.create(map.keySet().toArray()));
	}

	@Override
	public boolean equals(ASet<T> o) {
		if (o == this) return true;
		Set<T> other = (Set<T>) o;
		return map.equalsKeys(other.map);
	}

	@Override
	public void validate() throws InvalidDataException {
		super.validate();
		map.mapEntries(e -> {
			if (e.getValue() != Boolean.TRUE) {
				Object key = e.getKey();
				throw Utils.sneakyThrow(new InvalidDataException(
						"Set must have true entries in underlying map with key: " + key, this));
			}
			return e;
		});
	}

	@Override
	public void validateCell() throws InvalidDataException {
		map.validateCell();
	}

	@Override
	public <R> ASet<R> map(Function<? super T, ? extends R> mapper) {
		return Set.create(this.toVector().map(mapper));
	}
}
