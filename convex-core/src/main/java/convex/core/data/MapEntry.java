package convex.core.data;

import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import convex.core.data.type.AType;
import convex.core.data.type.Types;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;
import convex.core.util.ErrorMessages;

/**
 * Map.Entry implementation for persistent maps. This is primarily intended as an efficient 
 * implementation class for handling entries in Convex maps, and also to support the Java Map.Entry
 * interface for compatibility and developer convenience.
 * 
 * From a CVM perspective, a MapEntry is just a regular 2 element Vector.
 * 
 * Contains exactly 2 elements, one for key and one for value
 * 
 * Implements Comparable using the Hash value of keys.
 *
 * @param <K> The type of keys
 * @param <V> The type of values
 */
public class MapEntry<K extends ACell, V extends ACell> extends AMapEntry<K, V> implements Comparable<MapEntry<K, V>> {

	private final Ref<K> keyRef;
	private final Ref<V> valueRef;

	private MapEntry(Ref<K> key, Ref<V> value) {
		super(2);
		this.keyRef = key;
		this.valueRef = value;
	}
	
	@Override
	public AType getType() {
		return Types.VECTOR;
	}

	@SuppressWarnings("unchecked")
	public static <K extends ACell, V extends ACell> MapEntry<K, V> fromRefs(Ref<? extends K> keyRef, Ref<? extends V> valueRef) {
		// ensure we have a hash at least
		return new MapEntry<K, V>((Ref<K>) keyRef, (Ref<V>) valueRef);
	}

	/**
	 * Creates a new MapEntry with the provided key and value
	 * @param <K> Type of Key
	 * @param <V> Type of value
	 * @param key Key to use for MapEntry
	 * @param value Value to use for MapEntry
	 * @return New MapEntry instance
	 */
	public static <K extends ACell, V extends ACell> MapEntry<K, V> create(K key, V value) {
		return fromRefs(Ref.get(key), Ref.get(value));
	}
	
	/**
	 * Create a map entry, converting key and value to correct CVM types.
	 * @param <K> Type of Keys
	 * @param <V> Type of Values
	 * @param key Key to use for map entry
	 * @param value Value to use for map entry
	 * @return New MapEntry
	 */
	public static <K extends ACell, V extends ACell> MapEntry<K, V> of(Object key, Object value) {
		return create(RT.cvm(key),RT.cvm(value));
	}
	
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public static MapEntry convertOrNull(AVector v) {
		if (v.count()!=2) return null;
		return fromRefs(v.getElementRef(0),v.getElementRef(1));
	}

	@Override
	public MapEntry<K, V> withValue(V value) {
		if (value == getValue()) return this;
		return new MapEntry<K, V>(keyRef, Ref.get(value));
	}

	@SuppressWarnings("unchecked")
	@Override
	public MapEntry<K,V> assoc(long i, ACell a) {
		if (i == 0) return withKey((K) a);
		if (i == 1) return withValue((V) a);
		return null;
	}

	@Override
	protected MapEntry<K, V> withKey(K key) {
		if (key == getKey()) return this;
		return new MapEntry<K, V>(Ref.get(key), valueRef);
	}

	@Override
	public K getKey() {
		return keyRef.getValue();
	}

	@Override
	public <R extends ACell> AVector<R> map(Function<? super ACell, ? extends R> mapper) {
		return Vectors.of(mapper.apply(getKey()), mapper.apply(getValue()));
	}

	@Override
	public <R> R reduce(BiFunction<? super R, ? super ACell, ? extends R> func, R value) {
		R result = func.apply(value, getKey());
		result = func.apply(result, getKey());
		return result;
	}

	@SuppressWarnings("unchecked")
	@Override
	protected <R> void copyToArray(R[] arr, int offset) {
		arr[offset] = (R) getKey();
		arr[offset + 1] = (R) getValue();
	}

	/**
	 * Gets the Hash of the key for this {@linkplain MapEntry}
	 * 
	 * @return the Hash of the Key
	 */
	public Hash getKeyHash() {
		return getKeyRef().getHash();
	}

	@Override
	public V getValue() {
		return valueRef.getValue();
	}

	public Ref<K> getKeyRef() {
		return keyRef;
	}

	public Ref<V> getValueRef() {
		return valueRef;
	}

	@Override
	public int compareTo(MapEntry<K, V> o) {
		if (this == o) return 0;
		return keyRef.compareTo(o.keyRef);
	}

	@Override
	public final int getRefCount() {
		return 2;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <R extends ACell> Ref<R> getRef(int i) {
		if ((i >> 1) != 0) throw new IndexOutOfBoundsException(i);
		return (Ref<R>) ((i == 0) ? keyRef : valueRef);
	}

	@SuppressWarnings("unchecked")
	@Override
	public MapEntry<K, V> updateRefs(IRefFunction func) {
		Ref<K> newKeyRef = (Ref<K>) func.apply(keyRef);
		Ref<V> newValueRef = (Ref<V>) func.apply(valueRef);
		
		// Keep this instance if no change
		if ((keyRef == newKeyRef) && (valueRef == newValueRef)) return this;
		
		MapEntry<K, V> result= new MapEntry<K, V>(newKeyRef, newValueRef);
		result.attachEncoding(encoding); // this is an optimisation to avoid re-encoding
		return result;
	}

	@Override
	public boolean equals(AVector<? super ACell> o) {
		if (o==null) return false;
		if (o==this) return true;
		AVector<?> v=(AVector<?>) o;
		if (v.count()!=2) return false;
		return getEncoding().equals(o.getEncoding());
	}

	public boolean equals(MapEntry<K, V> b) {
		if (this == b) return true;
		return keyRef.equals(b.keyRef) && valueRef.equals(b.valueRef);
	}

	/**
	 * Checks if the keys of two map entries are equal
	 * 
	 * @param b MapEntry to compare with this MapEntry
	 * @return true if this entry's key equals that of the other entry, false
	 *         otherwise.
	 */
	public boolean keyEquals(MapEntry<K, V> b) {
		return keyRef.equals(b.keyRef);
	}

	@Override
	public ACell get(long i) {
		if (i == 0) return getKey();
		if (i == 1) return getValue();
		throw new IndexOutOfBoundsException(ErrorMessages.badIndex(i));
	}

	@SuppressWarnings("unchecked")
	@Override
	public Ref<ACell> getElementRef(long i) {
		if (i == 0) return (Ref<ACell>) keyRef;
		if (i == 1) return (Ref<ACell>) valueRef;
		throw new IndexOutOfBoundsException(ErrorMessages.badIndex(i));
	}
	
	@SuppressWarnings("unchecked")
	@Override
	protected Ref<ACell> getElementRefUnsafe(long i) {
		if (i == 0) return (Ref<ACell>) keyRef;
		return (Ref<ACell>) valueRef;
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.VECTOR;
		return encodeRaw(bs,pos);
	}
	
	/**
	 * Writes the raw MapEntry content. Puts the key and value Refs onto the given
	 * ByteBuffer
	 * 
	 * @param bs Byte array to write to
	 * @return Updated position after writing
	 */
	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos = Format.writeVLQCount(bs,pos, 2); // Size of 2, to match VectorLeaf encoding
		return encodeRefs(bs,pos);
	}
	
	int encodeRefs(byte[] bs, int pos) {
		pos = keyRef.encode(bs,pos);
		pos = valueRef.encode(bs,pos);
		return pos;
	}

	@Override
	public int estimatedEncodingSize() {
		return 2+Format.MAX_EMBEDDED_LENGTH*2; // header plus count two embedded objects
	}

	@SuppressWarnings("unchecked")
	@Override
	public void visitElementRefs(Consumer<Ref<ACell>> f) {
		f.accept((Ref<ACell>) keyRef);
		f.accept((Ref<ACell>) valueRef);
	}

	@Override
	public AVector<ACell> concat(ASequence<?> b) {
		if (b.isEmpty()) return this;
		return toVector().concat(b);
	}

	@Override
	public void validateCell() throws InvalidDataException {
		// Nothing to do, a map entry is always valid in itself
	}

	@Override
	public boolean isCanonical() {
		return true;
	}

	@Override
	public AVector<ACell> toCanonical() {
		return this;
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public AVector<ACell> toVector() {
		return new VectorLeaf<ACell>(new Ref[] { keyRef, valueRef });
	}

}
