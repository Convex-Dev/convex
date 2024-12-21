package convex.core.data;

import java.util.Collection;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

import convex.core.cpos.Block;
import convex.core.cvm.RecordFormat;
import convex.core.data.type.AType;
import convex.core.data.type.Types;
import convex.core.data.util.BlobBuilder;
import convex.core.lang.RT;

/**
 * Base class for CVM Record data types. 
 * 
 * Records are Map-like data structures with fixed sets of Keyword keys, and optional custom behaviour.
 * 
 * Ordering of fields is defined by the Record's RecordFormat
 *
 */
public abstract class ARecord<K extends ACell,V extends ACell> extends AMap<K,V> {

	// TODO: need a better default value?
	public static final ARecord<Keyword,ACell> DEFAULT_VALUE=Block.create(0, Vectors.empty());

	protected final byte tag;

	protected ARecord(byte tag, long n) {
		super(n);
		this.tag=tag;
	}
	
	
	@Override
	public byte getTag() {
		return tag;
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
	protected ARecord<K,V> toCanonical() {
		// Should already be canonical
		return this;
	}
	
	/**
	 * Gets a vector of keys for this record
	 * 
	 * @return Vector of Keywords
	 */
	@Override
	public abstract AVector<K> getKeys();
	
	/**
	 * Gets a vector of values for this Record, in format-determined order
	 * 
	 * @return Vector of Values
	 */
	@Override
	public abstract AVector<V> values();
	
	/**
	 * Gets the record field content for a given key, or null if not found.
	 * @param key Key to look up in this record
	 * @return Field value for the given key
	 */
	@Override
	public abstract V get(ACell key);
	
	/**
	 * Gets the record field content for a given key, or null if not found.
	 * @param key Key to look up in this record
	 * @return Field value for the given key
	 */
	public abstract ACell get(Keyword key);
	
	/**
	 * Gets an array containing all values in this record, in format-defined key order.
	 * @return Array of Values in this record
	 */
	public ACell[] getValuesArray() {
		return values().toCellArray();
	}
	
	@Override
	public boolean containsKey(ACell key) {
		RecordFormat format=getFormat();
		if (format==null) return false;
		return format.containsKey(key);
	}

	@Override
	public boolean containsValue(ACell value) {
		return values().contains(value);
	}

	@Override
	public abstract java.util.Set<K> keySet();

	@Override
	public Set<Entry<K, V>> entrySet() {
		return toHashMap().entrySet();
	}

	@Override
	public AMap<K, V> assoc(ACell key, ACell value) {
		return toHashMap().assoc(key, value);
	}
	
	public AMap<K, V> dissoc(Keyword key) {
		if (!containsKey(key)) return this;
		return toHashMap().dissoc(key);
	}

	@Override
	public final AMap<K, V> dissoc(ACell key) {
		if (!containsKey(key)) return this;
		return toHashMap().dissoc(key);
	}

	@Override
	public MapEntry<K, V> getKeyRefEntry(Ref<ACell> keyRef) {
		return getEntry(keyRef.getValue());
	}

	@Override
	protected void accumulateEntries(Collection<Entry<K, V>> h) {
		for (long i=0; i<count; i++) {
			h.add(entryAt(i));
		}
	}

	@Override
	protected void accumulateKeySet(Set<K> h) {
		AVector<K> keys=getKeys();
		for (long i=0; i<count; i++) {
			h.add(keys.get(i));
		}
	}

	@Override
	protected void accumulateValues(java.util.List<V> al) {
		toHashMap().accumulateValues(al);
	}

	@Override
	public void forEach(BiConsumer<? super K, ? super V> action) {
		throw new UnsupportedOperationException();
	}

	@Override
	public AMap<K,V> assocEntry(MapEntry<K, V> e) {
		return assoc(e.getKey(),e.getValue());
	}


	
	@Override
	public boolean print(BlobBuilder sb, long limit) {
		AString tag=getType().getTag();
		if (tag!=null) {
			sb.append(tag);
			sb.append(' ');
		}
		
		sb.append('{');
		long n=count();
		RecordFormat format=getFormat();
		ACell[] vs=getValuesArray();
		for (long i=0; i<n; i++) {
			Keyword k=format.getKey(i);
			if (!RT.print(sb,k,limit)) return false;
			sb.append(' ');
			ACell v=vs[(int)i];
			if (!RT.print(sb,v,limit)) return false;
			if (i<(n-1)) sb.append(',');
		}
		sb.append('}');
		return sb.check(limit);
	}

	@SuppressWarnings("unchecked")
	@Override
	public MapEntry<K, V> getEntry(ACell k) {
		V v=get(k);
		if ((v==null)&&!containsKey(k)) return null; //if null, need to check if key exists
		return MapEntry.create((K)k,v);
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public V get(ACell key, ACell notFound) {
		V v=get(key);
		if ((v==null)&&!containsKey(key)) return (V) notFound; //if null, need to check if key exists
		return v;
	}

	@Override
	public <R> R reduceValues(BiFunction<? super R, ? super V, ? extends R> func, R initial) {
		for (int i=0; i<count; i++) {
			initial=func.apply(initial, entryAt(i).getValue());
		}
		return initial;
	}	

	@Override
	public <R> R reduceEntries(BiFunction<? super R, MapEntry<K,V>, ? extends R> func, R initial) {
		for (int i=0; i<count; i++) {
			initial=func.apply(initial, entryAt(i));
		}
		return initial;
	}

	/**
	 * Converts this record to a HashMap
	 * @return HashMap instance
	 */
	protected AHashMap<K,V> toHashMap() {
		AHashMap<K,V> m=Maps.empty();
		for (int i=0; i<count; i++) {
			m=m.assocEntry(entryAt(i));
		}
		return m;
	}

	@Override
	protected MapEntry<K, V> getEntryByHash(Hash hash) {
		return toHashMap().getEntryByHash(hash);
	}

	@Override
	public AMap<K, V> empty() {
		// coerce to AHashMap since we are removing all keys
		return Maps.empty();
	}

	/**
	 * Gets the RecordFormat instance that describes this Record's layout
	 * @return RecordFormat instance
	 */
	public abstract RecordFormat getFormat();
	


}
