package  convex.benchmarks;

import java.util.concurrent.ArrayBlockingQueue;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

import convex.core.util.Utils;

public class ThreadCoordinationBenchmark {

	public static final ArrayBlockingQueue<Object> INPUT =new ArrayBlockingQueue<>(100);
	public static final ArrayBlockingQueue<Object> OUTPUT =new ArrayBlockingQueue<>(100);
	
	static {
		Thread processor = new Thread(()->{
			while (true) {
				try {
					Object o;
					o = INPUT.take();
					OUTPUT.put(o);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		});
		processor.setDaemon(true);
		processor.start();
	}
	
	@Benchmark
	public void pushThroughQueue() {
		try {
			INPUT.put(1L);
			OUTPUT.take();
		} catch (InterruptedException e) {
			throw Utils.sneakyThrow(e);
		}
	}

	public static void main(String[] args) throws Exception {
		Options opt = Benchmarks.createOptions(ThreadCoordinationBenchmark.class);
		new Runner(opt).run();
	}

}
