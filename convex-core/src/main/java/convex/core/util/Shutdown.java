package convex.core.util;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * So the JVM doesn't give us a nice way to run shutdown hooks in a defined order.
 *
 * This class enables us to do just that!
 */
public class Shutdown {

	public static final int CLIENTHTTP = 60;
	public static final int SERVER = 80;
	public static final int CONNECTION = 90;
	public static final int ETCH = 100;
	public static final int EXECUTOR = 110;
	public static final int CLI = 120;
	

	static {
		try {
			Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
				@Override
				public void run() {
					Shutdown.shutdownNow();
				}
			},"Convex Shutdown"));
		} catch(Exception e) {
			// Ignore, already shutting down
		}
	}

	protected static class Group {
		protected final int level;

		public Group(int level) {
			this.level=level;
		}
		
		private final IdentityHashMap<Runnable, Runnable> hookSet=new IdentityHashMap<>();

		public synchronized void addHook(Runnable r) {
			hookSet.put(r, r);
		}

		public synchronized void removeHook(Runnable r) {
			hookSet.remove(r);
		}

		public void runHooks() {
			Runnable[] hooks;
			synchronized (this) {
				hooks=hookSet.keySet().toArray(Runnable[]::new);
				hookSet.clear();
			}
			// Clear the registry before invoking callbacks. A callback may therefore
			// safely deregister itself while it closes its resource.
			for (Runnable r:hooks) {
				try {
					r.run();
				} catch (Throwable t) {
					t.printStackTrace();
					// Otherwise ignore. This is the same as what the JVM shutdown does
				}
			}
		}

	}

	// Concurrent map: addHook may be called from multiple threads (e.g. parallel
	// server / node launches). computeIfAbsent is atomic, so the get-or-create
	// cannot lose an update or corrupt the map, and ordering is preserved for
	// shutdownNow(). Group guards its own hookSet. (#604)
	private static final ConcurrentSkipListMap<Integer, Group> order=new ConcurrentSkipListMap<>();

	/**
	 * Add a Runnable shutdown hook with the given priority. Lower priority numbers will
	 * be executed first.
	 *
	 * @param priority Priority number for shutdown hook
	 * @param shutdownTask Runnable instance to execute on shutdown
	 */
	public static void addHook(int priority,Runnable shutdownTask) {
		Group g=order.computeIfAbsent(priority, Group::new);
		g.addHook(shutdownTask);
	}

	/**
	 * Removes a previously registered shutdown task by identity. Empty priority
	 * groups are deliberately retained: removing them from the concurrent map
	 * could race with another registration into the same group.
	 *
	 * @param priority priority used for registration
	 * @param shutdownTask exact Runnable instance supplied to {@link #addHook}
	 */
	public static void removeHook(int priority, Runnable shutdownTask) {
		Group g=order.get(priority);
		if (g!=null) g.removeHook(shutdownTask);
	}

	/**
	 * Execute all hooks in priority order. Called automatically by the JVM
	 * shutdown process, but can also be called explicitly for clean
	 * programmatic shutdown (e.g. after closing all servers in a demo).
	 *
	 * <p>Hooks are cleared after execution, so a subsequent JVM shutdown
	 * hook invocation becomes a no-op.
	 */
	public static void shutdownNow() {
		for (Map.Entry<Integer,Group> me: order.entrySet()) {
			me.getValue().runHooks();
		}
		order.clear();
	}
}
