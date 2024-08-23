package convex.core.data;

import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

import convex.core.Block;
import convex.core.data.type.AType;
import convex.core.data.type.Types;
import convex.core.lang.RecordFormat;

/**
 * Base class for Record data types. 
 * 
 * Records are Map-like data structures with fixed sets of Keyword keys, and optional custom behaviour.
 * 
 * Ordering of fields is defined by the Record's RecordFormat
 *
 */
public abstract class ARecord extends AMap<Keyword,ACell> {

	// TODO: need a better default value?
	public static final ARecord DEFAULT_VALUE=Block.create(0, Vectors.empty());

	protected ARecord(long n) {
		super(n);
	}
	
	public AType getType() {
		return Types.RECORD;
	}
	
	@Override
	public int estimatedEncodingSize() {
		return (int) (Format.MAX_EMBEDDED_LENGTH*count);
	}
	
	@Override
	public boolean isCanonical() {
		// Records should always be canonical
		return true;
	}
	
	@Override
	protected ARecord toCanonical() {
		// Should already be canonical
		return this;
	}

	@Override public boolean isCVMValue() {
		return true;
	}
	


	/**
	 * Gets a vector of keys for this record
	 * 
	 * @return Vector of Keywords
	 */
	@Override
	public final AVector<Keyword> getKeys() {
		return getFormat().getKeys();
	}
	
	/**
	 * Gets a vector of values for this Record, in format-determined order
	 * 
	 * @return Vector of Values
	 */
	@Override
	public AVector<ACell> values() {
		int n=size();
		ACell[] os=new ACell[n];
		RecordFormat format=getFormat();
		for (int i=0; i<n; i++) {
			os[i]=get(format.getKey(i));
		}
		return Vectors.wrap(os);
	}
	
	/**
	 * Gets the record field content for a given key, or null if not found.
	 * @param key Key to look up in this record
	 * @return Field value for the given key
	 */
	@Override
	public final ACell get(ACell key) {
		if (key instanceof Keyword) return get((Keyword)key);
		return null;
	}
	
	/**
	 * Gets the record field content for a given key, or null if not found.
	 * @param key Key to look up in this record
	 * @return Field value for the given key
	 */
	public abstract ACell get(Keyword key);

	/**
	 * Gets the tag byte for this record type. The Tag is the byte used to identify the
	 * record in the binary encoding.
	 * 
	 * @return Record tag byte
	 */
	@Override
	public abstract byte getTag();
	
	/**
	 * Gets an array containing all values in this record, in format-defined key order.
	 * @return Array of Values in this record
	 */
	public ACell[] getValuesArray() {
		int n=size();
		ACell[] result=new ACell[n];
		AVector<Keyword> keys=getFormat().getKeys();
		for (int i=0; i<n; i++) {
			result[i]=get(keys.get(i));
		}
		return result;
	}
	
	@Override
	public boolean containsKey(ACell key) {
		return getFormat().containsKey(key);
	}

	@Override
	public boolean containsValue(ACell value) {
		return values().contains(value);
	}

	@Override
	public java.util.Set<Keyword> keySet() {
		return getFormat().keySet();
	}

	@Override
	public Set<Entry<Keyword, ACell>> entrySet() {
		return toHashMap().entrySet();
	}

	@Override
	public AMap<Keyword, ACell> assoc(ACell key, ACell value) {
		return toHashMap().assoc(key, value);
	}
	
	public AMap<Keyword, ACell> dissoc(Keyword key) {
		if (!containsKey(key)) return this;
		return toHashMap().dissoc(key);
	}

	@Override
	public final AMap<Keyword, ACell> dissoc(ACell key) {
		if (!containsKey(key)) return this;
		return toHashMap().dissoc(key);
	}

	@Override
	public MapEntry<Keyword, ACell> getKeyRefEntry(Ref<ACell> keyRef) {
		// TODO: could maybe be more efficient?
		return getEntry(keyRef.getValue());
	}

	@Override
	protected void accumulateEntrySet(Set<Entry<Keyword, ACell>> h) {
		for (long i=0; i<count; i++) {
			h.add(entryAt(i));
		}
	}

	@Override
	protected void accumulateKeySet(Set<Keyword> h) {
		AVector<Keyword> keys=getFormat().getKeys();
		for (long i=0; i<count; i++) {
			h.add(keys.get(i));
		}
	}

	@Override
	protected void accumulateValues(java.util.List<ACell> al) {
		toHashMap().accumulateValues(al);
	}

	@Override
	public void forEach(BiConsumer<? super Keyword, ? super ACell> action) {
		throw new UnsupportedOperationException();
	}

	@Override
	public AMap<Keyword,ACell> assocEntry(MapEntry<Keyword, ACell> e) {
		return assoc(e.getKey(),e.getValue());
	}

	@Override
	public MapEntry<Keyword, ACell> entryAt(long i) {
		if ((i<0)||(i>=count)) throw new IndexOutOfBoundsException("Index:"+i);
		Keyword k=getFormat().getKeys().get(i);
		return getEntry(k);
	}

	@Override
	public MapEntry<Keyword, ACell> getEntry(ACell k) {
		if (!containsKey(k)) return null;
		return MapEntry.create((Keyword)k,get(k));
	}

	@Override
	public <R> R reduceValues(BiFunction<? super R, ? super ACell, ? extends R> func, R initial) {
		for (int i=0; i<count; i++) {
			initial=func.apply(initial, entryAt(i).getValue());
		}
		return initial;
	}	

	@Override
	public <R> R reduceEntries(BiFunction<? super R, MapEntry<Keyword, ACell>, ? extends R> func, R initial) {
		for (int i=0; i<count; i++) {
			initial=func.apply(initial, entryAt(i));
		}
		return initial;
	}

	/**
	 * Converts this record to a HashMap
	 * @return HashMap instance
	 */
	protected AHashMap<Keyword,ACell> toHashMap() {
		AHashMap<Keyword,ACell> m=Maps.empty();
		for (int i=0; i<count; i++) {
			m=m.assocEntry(entryAt(i));
		}
		return m;
	}

	@Override
	protected MapEntry<Keyword, ACell> getEntryByHash(Hash hash) {
		return toHashMap().getEntryByHash(hash);
	}

	@Override
	public AHashMap<Keyword, ACell> empty() {
		// coerce to AHashMap since we are removing all keys
		return Maps.empty();
	}

	/**
	 * Gets the RecordFormat instance that describes this Record's layout
	 * @return RecordFormat instance
	 */
	public abstract RecordFormat getFormat();
	


}
