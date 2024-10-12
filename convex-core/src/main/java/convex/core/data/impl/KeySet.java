package convex.core.data.impl;

import convex.core.data.ACell;
import convex.core.data.ACollection;
import convex.core.data.AHashMap;
import convex.core.data.AHashSet;
import convex.core.data.ASet;
import convex.core.data.Hash;
import convex.core.data.MapEntry;
import convex.core.data.Maps;
import convex.core.data.Ref;
import convex.core.exceptions.InvalidDataException;
import convex.core.exceptions.TODOException;

public class KeySet<K extends ACell, V extends ACell> extends ADerivedSet<K,K,V>{

	protected KeySet(AHashMap<K, V> map) {
		super(map);
		// TODO Auto-generated constructor stub
	}
	
	public static <K extends ACell, V extends ACell> KeySet<K,V> create(AHashMap<K, V> map) {
		return new KeySet<>(map);
	}

	@Override
	public int estimatedEncodingSize() {
		return getCanonical().estimatedEncodingSize();
	}

	@Override
	public ASet<K> include(K a) {
		return getCanonicalSet().include(a);
	}

	protected ASet<K> getCanonicalSet() {
		return getCanonical();
	}

	@Override
	public ASet<K> exclude(ACell a) {
		return (ASet<K>) getCanonicalSet().exclude(a);
	}

	@Override
	public ASet<K> includeAll(ASet<? extends K> elements) {
		return getCanonicalSet().includeAll(elements);
	}

	@SuppressWarnings({ "unchecked", "rawtypes"})
	@Override
	public ASet<K> excludeAll(ASet<K> elements) {
		return (ASet<K>) getCanonicalSet().excludeAll((ASet) elements);
	}

	@Override
	public ASet<K> conjAll(ACollection<? extends K> xs) {
		return getCanonicalSet().conjAll(xs);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Override
	public ASet<K> disjAll(ACollection<K> xs) {
		return getCanonicalSet().disjAll((ACollection)xs);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Override
	public ASet<K> intersectAll(ASet<K> xs) {
		return getCanonicalSet().intersectAll((ASet)xs);
	}

	@Override
	public boolean contains(ACell o) {
		return map.containsKey(o);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Override
	public ASet<K> includeRef(Ref<K> ref) {
		return getCanonicalSet().includeRef((Ref) ref);
	}

	@Override
	public ASet<K> conj(ACell a) {
		return getCanonicalSet().conj(a);
	}

	@SuppressWarnings("unchecked")
	@Override
	public Ref<K> getValueRef(ACell k) {
		MapEntry<K, V> e = map.getEntry(k);
		if (e==null ) return null;
		return (Ref<K>) e.getValueRef();
	}

	@Override
	protected Ref<K> getRefByHash(Hash hash) {
		throw new TODOException();
	}

	@Override
	public boolean containsAll(ASet<?> b) {
		return getCanonicalSet().containsAll(b);
	}

	@Override
	public ASet<K> slice(long start, long end) {
		return (ASet<K>) getCanonicalSet().slice(start,end);
	}

	@Override
	public int encode(byte[] bs, int pos) {
		return getCanonicalSet().encode(bs,pos);
	}

	@Override
	protected <R> void copyToArray(R[] arr, int offset) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public Ref<K> getElementRef(long index) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		// Nothing to validate
	}

	@Override
	public boolean equals(ACell a) {
		return getCanonicalSet().equals(a);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		throw new UnsupportedOperationException();
	}

	@Override
	protected AHashSet<K> toCanonical() {
		throw new TODOException();
		// return map.buildKeySet();
	}

	@Override
	public boolean isCVMValue() {
		return true;
	}

	@Override
	public int getRefCount() {
		throw new UnsupportedOperationException();
	}

	@Override
	protected Hash getFirstHash() {
		// TODO Auto-generated method stub
		return Maps.getFirstHash(map);
	}

}
