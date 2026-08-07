package convex.benchmarks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.List;

import org.junit.jupiter.api.Test;

public class EtchConcurrentBenchmarkTest {
	@Test
	public void testSmallConcurrentWorkloadAndReopen() throws Exception {
		EtchConcurrentBenchmark.Options options=EtchConcurrentBenchmark.Options.parse(new String[] {
				"--config","v2-mapped",
				"--config","v3-aes-mapped",
				"--readers","2",
				"--writers","2",
				"--syncs","5",
				"--reads","5000",
				"--writes","500",
				"--preload","1000",
				"--payload-longs","4",
				"--miss-percent","25",
				"--recent-percent","50",
				"--verify-samples","200"
		});
		List<EtchConcurrentBenchmark.RunResult> results;
		try (PrintStream output=new PrintStream(OutputStream.nullOutputStream())) {
			results=EtchConcurrentBenchmark.run(options,output);
		}

		assertEquals(2,results.size());
		for (EtchConcurrentBenchmark.RunResult result:results) {
			assertEquals(5000L,result.cachedHits()+result.directHits()+result.misses());
			assertEquals(500L,result.writes());
			assertEquals(5L,result.syncs());
			assertTrue(result.logicalBytes()>0L);
			assertTrue(result.totalNanos()>0L);
		}
	}
}
