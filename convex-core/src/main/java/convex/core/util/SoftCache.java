package convex.core.util;

import java.lang.ref.SoftReference;
import java.util.AbstractMap;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Cache with weak keys and soft referenced values
 */
public class SoftCache<K,V> extends AbstractMap<K,V> {
	private WeakHashMap<K,SoftReference<V>> cache=new WeakHashMap<K,SoftReference<V>>();
	
	public V get(Object key) {
		SoftReference<V> sr=getSoftReference(key);
		if (sr==null) return null;
		return sr.get();
	}

	public SoftReference<V> getSoftReference(Object key) {
		return cache.get(key);
	}
	
	@Override
	public V put(K key, V value) {
		SoftReference<V> sr=new SoftReference<>(value);
		cache.put(key, sr);
		return null;
	}
	
	public SoftReference<V> putReference(K key, SoftReference<V> valueRef) {
		return cache.put(key, valueRef);
	}

	@Override
	public Set<Entry<K, V>> entrySet() {
		HashSet<Entry<K, V>> result=new HashSet<Entry<K, V>>();
		
		for (Entry<K, SoftReference<V>> e: cache.entrySet()) {
			V val=e.getValue().get();
			if (val!=null) {
				result.add(new AbstractMap.SimpleEntry<>(e.getKey(),val));
			}
		}
		return result;
	}
}
