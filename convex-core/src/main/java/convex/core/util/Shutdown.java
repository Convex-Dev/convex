package convex.core.util;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * So the JVM doesn't give us a nice way to run shutdown hooks in a defined order.
 *
 * This class enables us to do just that!
 */
public class Shutdown {

	public static final int CLIENTHTTP = 60;
	public static final int SERVER = 80;
	public static final int ETCH = 100;
	public static final int CLI = 120;

	static {
		try {
			Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
				@Override
				public void run() {
					Shutdown.runHooks();
				}
			}));
		} catch(IllegalStateException e) {
			// Ignore, already shutting down
		}
	}

	private static class Group {
		private final IdentityHashMap<Runnable, Runnable> hookSet=new IdentityHashMap<>();

		public synchronized void addHook(Runnable r) {
			hookSet.put(r, r);
		}

		public synchronized void runHooks() {
			Collection<Runnable> hooks=hookSet.keySet();
			hooks.stream().forEach(r->{
				r.run();
			});
			hookSet.clear();
		}

	}

	private static final TreeMap<Integer, Group> order=new TreeMap<>();

	/**
	 * Add a Runnable shutdown hook with the given priority. Lower priority numbers will
	 * be executed first.
	 *
	 * @param priority Priority number for shutdown hook
	 * @param r
	 */
	public static synchronized void addHook(int priority,Runnable r) {
		Group g=order.get(priority);
		if (g==null) {
			g=new Group();
			order.put(priority, g);
		}
		g.addHook(r);
	}

	/**
	 * Execute all hooks. Called by standard Java shutdown process.
	 */
	private synchronized static void runHooks() {
		for (Map.Entry<Integer,Group> me: order.entrySet()) {
			me.getValue().runHooks();
		}
		order.clear();
	}
}
