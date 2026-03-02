package convex.core.util;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.util.AbstractMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache with soft referenced values
 * 
 * Very fast, clears map values, entries on GC pressure but otherwise keeps them around.
 */
public class SoftCache<K,V> extends AbstractMap<K,V> {
	
	private ConcurrentHashMap <K,SoftValueReference<K,V>> cache=new ConcurrentHashMap <K,SoftValueReference<K,V>>();
	
	private ReferenceQueue<V> queue=new ReferenceQueue<>();
	
	public V get(Object key) {
		expelStaleEntries();
		SoftReference<V> sr=getSoftReference(key);
		if (sr==null) return null;
		return sr.get();
	}

	public SoftReference<V> getSoftReference(Object key) {
		return cache.get(key);
	}
	
	@Override
	public void clear() {
		cache.clear();
	}
	
	@Override
	public V put(K key, V value) {
		SoftValueReference<K,V> sr=new SoftValueReference<>(key,value,queue);
		SoftValueReference<K,V> r=putReference(key, sr);
		if (r==null) r=sr;
		return r.get();
	}
	
	@SuppressWarnings("unchecked")
	private void expelStaleEntries() {
		SoftValueReference<K, V> ref;
        while ((ref = (SoftValueReference<K, V>) queue.poll()) != null) {
            cache.remove(ref.key,ref);
        }
	}

	public SoftValueReference<K,V> putReference(K key, SoftValueReference<K,V> valueRef) {
		expelStaleEntries();
		return cache.put(key, valueRef);
	}

	@Override
	public Set<Entry<K, V>> entrySet() {
		HashSet<Entry<K, V>> result=new HashSet<Entry<K, V>>();
		
		for (Entry<K, SoftValueReference<K,V>> e: cache.entrySet()) {
			V val=e.getValue().get();
			if (val!=null) {
				result.add(new AbstractMap.SimpleEntry<>(e.getKey(),val));
			}
		}
		return result;
	}
	
	// Custom SoftReference that knows its own key
	public static class SoftValueReference<K, V> extends SoftReference<V> {
        final K key;

        SoftValueReference(K key, V value, ReferenceQueue<V> queue) {
            super(value, queue);
            this.key = key;
        }
    }
}
