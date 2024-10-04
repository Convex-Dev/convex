package convex.core.util;

import java.util.function.Consumer;

public class BenchUtils {

	public static void benchMark(int runs, Runnable r, Consumer<Double> report) {
		for (int i=0; i< runs; i++) {
			long start=System.nanoTime();
			r.run();
			long end=System.nanoTime();
			report.accept((1e-9*(end-start)));
		}
	}

}
