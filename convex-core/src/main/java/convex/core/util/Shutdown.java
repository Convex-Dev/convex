package convex.core.util;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
	
	private static final Logger log=LoggerFactory.getLogger(Shutdown.class.getName());

	static {
		try {
			Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
				@Override
				public void run() {
					log.debug("Running Convex shutdown hooks");
					Shutdown.runHooks();
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

		public synchronized void runHooks() {
			// System.out.println("Running shutdown hooks at level: "+level);
			Collection<Runnable> hooks=hookSet.keySet();
			hooks.stream().forEach(r->{
				log.trace("Running shutdown hook: "+Utils.getClassName(r));
				try {
					r.run();
				} catch (Throwable t) {
					t.printStackTrace();
					// Otherwise ignore. This is the same as what the JVM shutdown does
				}
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
	 * @param shutdownTask Runnable instance to execute on shutdown
	 */
	public static void addHook(int priority,Runnable shutdownTask) {
		Group g=order.get(priority);
		if (g==null) {
			g=new Group(priority);
			order.put(priority, g);
		}
		g.addHook(shutdownTask);
	}

	/**
	 * Execute all hooks. Called by standard Java shutdown process.
	 */
	private static void runHooks() {
		for (Map.Entry<Integer,Group> me: order.entrySet()) {
			log.debug("Running shutdown hooks at level: "+me.getKey());
			me.getValue().runHooks();
		}
		order.clear();
		log.debug("Convex shutdown hooks complete");
	}
	
	public void shoutDownNow() {
		runHooks();
	}
}
