package convex.core.store;

import java.io.IOException;

import convex.etch.EtchStore;

public class Stores {

	// Default store
	private static AStore defaultStore=null;
	
	// Configured global store
	private static AStore globalStore=null;
	
	// Thread local current store, in case servers want different stores
	private static final ThreadLocal<AStore> currentStore = new ThreadLocal<>() {
		@Override
		protected AStore initialValue() {
			return getGlobalStore();
		}
	};
	
	/**
	 * Gets the current (thread-local) Store instance. This is initialised to be the
	 * global store, but can be changed with Stores.setCurrent(...)
	 * 
	 * @return Store for the current thread
	 */
	public static AStore current() {
		return Stores.currentStore.get();
	}

	/**
	 * Sets the current thread-local store for this thread
	 * 
	 * @param store Any AStore instance
	 */
	public static void setCurrent(AStore store) {
		currentStore.set(store);
	}
	
	private synchronized static AStore getDefaultStore() {
		if (defaultStore==null) {
			try {
				defaultStore=EtchStore.createTemp("convex-db");
			} catch (IOException e) {
				throw new Error(e);
			};
		}
		return defaultStore;
	}

	/**
	 * Gets the global store instance. If not previously set, a default temporary
	 * store will be created and used as the global store.
	 * 
	 * @return Current global store
	 */
	public static AStore getGlobalStore() {
		if (globalStore==null) {
			globalStore=getDefaultStore();
		}
		return globalStore;
	}

	/**
	 * Sets the global store for this JVM. Global store is the store used for 
	 * any new thread.
	 * 
	 * @param store Store instance to use as global store
	 */
	public static void setGlobalStore(EtchStore store) {
		if (store==null) throw new IllegalArgumentException("Cannot set global store to null)");
		globalStore=store;
	}
}
