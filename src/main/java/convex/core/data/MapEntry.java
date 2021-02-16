package convex.core.data;

import java.nio.ByteBuffer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import convex.core.crypto.Hash;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;
import convex.core.util.Errors;
import convex.core.util.Utils;

/**
 * Map.Entry implementation for persistent maps.
 * 
 * Contains exactly 2 Refs, one for key and one for value
 * 
 * Implements Comparable using the hash value of keys.
 *
 * @param <K> The type of keys
 * @param <V> The type of values
 */
public class MapEntry<K extends ACell, V extends ACell> extends AMapEntry<K, V> implements Comparable<MapEntry<K, V>> {

	private final Ref<K> keyRef;
	private final Ref<V> valueRef;

	private MapEntry(Ref<K> key, Ref<V> value) {
		this.keyRef = key;
		this.valueRef = value;
	}

	@SuppressWarnings("unchecked")
	public static <K extends ACell, V extends ACell> MapEntry<K, V> createRef(Ref<? extends K> keyRef, Ref<? extends V> valueRef) {
		// ensure we have a hash at least
		return new MapEntry<K, V>((Ref<K>) keyRef, (Ref<V>) valueRef);
	}

	public static <K extends ACell, V extends ACell> MapEntry<K, V> create(K key, V value) {
		return createRef(Ref.get(key), Ref.get(value));
	}
	
	/**
	 * Create a map entry, coerving key and value to correct CVM types.
	 * @param <K> Type of Keys
	 * @param <V> Type of Values
	 * @param key Key to use for map entry
	 * @param value Value to use for map entry
	 * @return New MapEntry
	 */
	public static <K extends ACell, V extends ACell> MapEntry<K, V> of(Object key, Object value) {
		return create(RT.cvm(key),RT.cvm(value));
	}

	@Override
	public MapEntry<K, V> withValue(V value) {
		if (value == getValue()) return this;
		return new MapEntry<K, V>(keyRef, Ref.get(value));
	}

	@SuppressWarnings("unchecked")
	@Override
	public <R extends ACell> AVector<R> assoc(long i, R a) {
		if (i == 0) return (AVector<R>) withKey((K) a);
		if (i == 1) return (AVector<R>) withValue((V) a);
		if (i== 2) return conj(a);
		throw Utils.sneakyThrow(new IndexOutOfBoundsException("Index: i"));
	}

	@Override
	protected AMapEntry<K, V> withKey(K key) {
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
	 * Gets the hash of the key for this MapEntry
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



	public static <K extends ACell, V extends ACell> MapEntry<K, V> read(ByteBuffer bb) throws BadFormatException {
		Ref<K> kr = Format.readRef(bb);
		Ref<V> vr = Format.readRef(bb);
		return new MapEntry<K, V>(kr, vr);
	}

	@Override
	public int compareTo(MapEntry<K, V> o) {
		if (this == o) return 0;
		return keyRef.compareTo(o.keyRef);
	}

	@Override
	public int getRefCount() {
		return 2;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <R extends ACell> Ref<R> getRef(int i) {
		if ((i >> 1) != 0) throw new IndexOutOfBoundsException(i);
		return (Ref<R>) (((i & 1) == 0) ? keyRef : valueRef);
	}

	@SuppressWarnings("unchecked")
	@Override
	public MapEntry<K, V> updateRefs(IRefFunction func) {
		Ref<K> newKeyRef = (Ref<K>) func.apply(keyRef);
		Ref<V> newValueRef = (Ref<V>) func.apply(valueRef);
		if ((keyRef == newKeyRef) && (valueRef == newValueRef)) return this;
		return new MapEntry<K, V>(newKeyRef, newValueRef);
	}

	@Override
	public void ednString(StringBuilder sb) {
		sb.append("#entry [" + Utils.ednString(getKey()) + "," + Utils.ednString(getValue()) + "]");
	}
	
	@Override
	public void print(StringBuilder sb) {
		sb.append('[');
		Utils.print(sb,getKey());
		sb.append(',');
		Utils.print(sb,getValue());
		sb.append(']');
	}

	@SuppressWarnings("unchecked")
	@Override
	public boolean equals(ACell o) {
		if (o instanceof MapEntry) return equals((MapEntry<K, V>) o);
		return false;
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
	@SuppressWarnings("unchecked")
	public <R extends ACell> AVector<R> toVector() {
		return new VectorLeaf<R>(new Ref[] { keyRef, valueRef });
	}

	@Override
	public int size() {
		return 2;
	}

	@Override
	public boolean isEmpty() {
		return false;
	}

	@Override
	public boolean contains(Object o) {
		return (Utils.equals(o, getKey()) || Utils.equals(o, getValue()));
	}

	@Override
	public ACell get(long i) {
		if (i == 0) return getKey();
		if (i == 1) return getValue();
		throw new IndexOutOfBoundsException(Errors.badIndex(i));
	}

	@SuppressWarnings("unchecked")
	@Override
	protected Ref<ACell> getElementRef(long i) {
		if (i == 0) return (Ref<ACell>) keyRef;
		if (i == 1) return (Ref<ACell>) valueRef;
		throw new IndexOutOfBoundsException(Errors.badIndex(i));
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.MAP_ENTRY;
		return encodeRaw(bs,pos);
	}
	
	/**
	 * Writes the raw MapEntry content. Puts the key and value Refs onto the given
	 * ByteBuffer
	 * 
	 * @param bb ByteBuffer to write to
	 * @return Updated ByteBuffer after writing
	 */
	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos = keyRef.encode(bs,pos);
		pos = valueRef.encode(bs,pos);
		return pos;
	}

	@Override
	public boolean isCanonical() {
		return true;
	}

	@Override
	public int estimatedEncodingSize() {
		return 1+Format.MAX_EMBEDDED_LENGTH*2; // header plus two embedded objects
	}

	@SuppressWarnings("unchecked")
	@Override
	public void visitElementRefs(Consumer<Ref<ACell>> f) {
		f.accept((Ref<ACell>) keyRef);
		f.accept((Ref<ACell>) valueRef);
	}

	@Override
	public <R  extends ACell> AVector<R> concat(ASequence<R> b) {
		long bLen = b.count();
		AVector<R> result = this.toVector();
		long i = 0;
		while (i < bLen) {
			result = result.conj(b.get(i));
			i++;
		}
		return result;
	}

	@Override
	public <R extends ACell> AVector<R> subVector(long start, long length) {
		AVector<R> vec=toVector();
		return vec.subVector(start, length);
	}

	@Override
	public void validate() throws InvalidDataException {
		super.validate();
		keyRef.validate();
		valueRef.validate();
	}


	@Override
	public void validateCell() throws InvalidDataException {
		// TODO: is there really Nothing to do?
	}



}
