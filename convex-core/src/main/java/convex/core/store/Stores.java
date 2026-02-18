package convex.core.store;

import java.io.IOException;

import convex.etch.EtchStore;

public class Stores {

	// Default store
	private static AStore defaultStore=null;
	
	// Configured global store
	private static volatile AStore globalStore=null;
	
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
