package convex.performance;

import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

public class Benchmarks {

	static Options createOptions(Class<?> c) {
		return new OptionsBuilder().include(c.getSimpleName()).warmupIterations(1).measurementIterations(5)
				.warmupTime(TimeValue.seconds(1)).measurementTime(TimeValue.seconds(1)).forks(0).build();
	}

}
