package convex.core.util;

import java.util.WeakHashMap;

/**
 * lightweight load monitor class.
 * 
 * Load is measured on a per-thread basis.
 */
public class LoadMonitor {

	public static class LoadMetrics {
		long last=0;
		boolean isUp=false;
		double load=0.0;
		
		public void update(boolean up, long ts) {
			long elapsed=ts-last;
			double decay=Math.pow(0.5,elapsed*0.001);
			load=(load*decay)+(1.0-decay)*(isUp?1.0:0.0);
			last=ts;
			isUp=up;
		}

	}
	
	private static final WeakHashMap<Thread, LoadMetrics> loads=new WeakHashMap<>();
	
    private static LoadMetrics createValue() {
		LoadMetrics lm=new LoadMetrics();
		long ts=System.currentTimeMillis();
		lm.last=ts;
        return lm;
    }
	
	public static double getLoad(Thread t) {
		LoadMetrics lm=get(t);
		return lm.load;
	}
	
	/**
	 * Signals start of load for current Thread
	 */
	public static void up() {
		Thread t=Thread.currentThread();
		LoadMetrics lm=get(t);
		long ts=System.currentTimeMillis();
		lm.update(true,ts);
	}

	/**
	 * Signals end of load for current Thread
	 */
	public static void down() {
		Thread t=Thread.currentThread();
		LoadMetrics lm=get(t);
		long ts=System.currentTimeMillis();
		lm.update(false,ts);
	}
	
	public static LoadMetrics get(Thread t) {
		LoadMetrics lm=loads.get(t);
		if (lm==null) {
			lm=createValue();
			loads.put(t, lm);
		}
		return lm;
	}
}
