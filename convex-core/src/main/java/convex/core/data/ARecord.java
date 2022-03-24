package convex.core.data;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

import convex.core.Block;
import convex.core.data.type.AType;
import convex.core.data.type.Types;
import convex.core.lang.impl.RecordFormat;
import convex.core.util.Utils;

/**
 * Base class for record data types. 
 * 
 * Records are map-like data structures with fixed sets of keys, and optional custom behaviour.
 * 
 * Ordering of fields is defined by the Record's RecordFormat
 *
 */
public abstract class ARecord extends AMap<Keyword,ACell> {

	protected final RecordFormat format;
	
	// TODO: need a better default value?
	public static final ARecord DEFAULT_VALUE=Block.create(0, Vectors.empty());

	protected ARecord(RecordFormat format) {
		super(format.count());
		this.format=format;
	}
	
	public AType getType() {
		return Types.RECORD;
	}
	
	@Override
	public int estimatedEncodingSize() {
		return (int) (Format.MAX_EMBEDDED_LENGTH*format.count());
	}
	
	@Override
	public boolean isCanonical() {
		// Records should always be canonical
		return true;
	}
	
	@Override
	public ARecord toCanonical() {
		// Should already be canonical
		return this;
	}
	
	@Override public final boolean isCVMValue() {
		return true;
	}
	
	/**
	 * Writes the raw fields of this record in declared order
	 * @param bs Array to write to
	 */
	@Override
	public int encodeRaw(byte[] bs, int pos) {
		List<Keyword> keys=getKeys();
		for (Keyword key: keys) {
			pos=Format.write(bs,pos, get(key));
		}
		return pos;
	}

	/**
	 * Gets a vector of keys for this record
	 * 
	 * @return Vector of Keywords
	 */
	public final AVector<Keyword> getKeys() {
		return format.getKeys();
	}
	
	/**
	 * Gets a vector of values for this record, in format-determined order
	 * 
	 * @return Vector of Values
	 */
	@Override
	public AVector<ACell> values() {
		int n=size();
		ACell[] os=new ACell[n];
		for (int i=0; i<n; i++) {
			os[i]=get(format.getKey(i));
		}
		return Vectors.create(os);
	}
	
	/**
	 * Gets the record field content for a given key, or null if not found.
	 * @param key Key to look up in this record
	 * @return Field value for the given key
	 */
	public final ACell get(Object key) {
		if (!(key instanceof Keyword)) return null;
		return get((Keyword)key);
	}
	
	/**
	 * Gets the record field content for a given key, or null if not found.
	 * @param key Key to look up in this record
	 * @return Field value for the given key
	 */
	@Override
	public abstract ACell get(ACell key);

	/**
	 * Gets the tag byte for this record type. The Tag is the byte used to identify the
	 * record in the binary encoding.
	 * 
	 * @return Record tag byte
	 */
	@Override
	public abstract byte getTag();
	
	@Override
	public int getRefCount() {
		long n=format.count();
		int count=0;
		for (int i=0; i<n; i++) {
			count+=Utils.refCount(get(getKeys().get(i)));
		}
		return count;
	}
	
	@Override
	public <R extends ACell> Ref<R> getRef(int index) {
		long n=size();
		int si=index;
		if (index<0) throw new IndexOutOfBoundsException("Negative ref index: "+index);
		for (int i=0; i<n; i++) {
			ACell v=get(getKeys().get(i));
			int rc=Utils.refCount(v);
			if (si<rc) return v.getRef(si);
			si-=rc;
		}
		throw new IndexOutOfBoundsException("Bad ref index: "+index);
	}

	@Override
	public ARecord updateRefs(IRefFunction func) {
		int n=size();		
		ACell[] newValues=new ACell[n];
		AVector<Keyword> keys=getKeys();
		for (int i=0; i<n; i++) {
			ACell v=get(keys.get(i));
			if (v!=null) v=v.updateRefs(func);
			newValues[i]=v;
		}
		return updateAll(newValues);
	}
	
	/**
	 * Gets an array containing all values in this record, in format-defined key order.
	 * @return Array of Values in this record
	 */
	public ACell[] getValuesArray() {
		int n=size();
		ACell[] result=new ACell[n];
		AVector<Keyword> keys=format.getKeys();
		for (int i=0; i<n; i++) {
			result[i]=get(keys.get(i));
		}
		return result;
	}
		
	/**
	 * Updates all values in this record, in declared field order.
	 * 
	 * Returns this if all values are identical.
	 * 
	 * @param newVals New values to replace current
	 * @return Updated Record
	 */
	protected abstract ARecord updateAll(ACell[] newVals);
	
	@Override
	public boolean containsKey(ACell key) {
		return format.containsKey(key);
	}

	@Override
	public boolean containsValue(Object value) {
		return values().contains(value);
	}

	@Override
	public java.util.Set<Keyword> keySet() {
		return format.keySet();
	}

	@Override
	public Set<Entry<Keyword, ACell>> entrySet() {
		return toHashMap().entrySet();
	}

	@Override
	public AMap<Keyword, ACell> assoc(ACell key, ACell value) {
		// TODO: OK to convert records to hashmaps?
		return toHashMap().assoc(key, value);
	}
	
	public AMap<Keyword, ACell> dissoc(Keyword key) {
		if (!containsKey(key)) return this;
		return toHashMap().dissoc(key);
	}

	@Override
	public AMap<Keyword, ACell> dissoc(ACell key) {
		if (!containsKey(key)) return this;
		return toHashMap().dissoc(key);
	}

	@Override
	public MapEntry<Keyword, ACell> getKeyRefEntry(Ref<ACell> ref) {
		// TODO: could maybe be more efficient?
		return getEntry(ref.getValue());
	}

	@Override
	protected void accumulateEntrySet(HashSet<Entry<Keyword, ACell>> h) {
		for (long i=0; i<count; i++) {
			h.add(entryAt(i));
		}
	}

	@Override
	protected void accumulateKeySet(HashSet<Keyword> h) {
		AVector<Keyword> keys=format.getKeys();
		for (long i=0; i<count; i++) {
			h.add(keys.get(i));
		}
	}

	@Override
	protected void accumulateValues(ArrayList<ACell> al) {
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
		Keyword k=format.getKeys().get(i);
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

	@Override
	public boolean equalsKeys(AMap<Keyword, ACell> map) {
		return toHashMap().equalsKeys(map);
	}

	/**
	 * Converts this record to a hashmap
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
	public RecordFormat getFormat() {
		return format;
	}
	


}
