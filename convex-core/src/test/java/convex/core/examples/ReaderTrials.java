package convex.core.examples;

import java.util.List;

import convex.core.cvm.State;
import convex.core.data.ACell;
import convex.core.init.Init;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.text.Text;
import convex.test.Samples;

/**
 * Test class for juice costs relative to execution time
 */
public class ReaderTrials {

	// Data file (printout of a genesis CVM state)
	static State s=Init.createState(List.of(Samples.KEY_PAIR.getAccountKey()));
	static String data=RT.print(s,10000000).toString();
	
	public static void main(String[] args) {
		System.out.println("Small vectors");
		runTrial("[1 2 3]",1000);
		runTrial("[1 2 3]",1000);
		runTrial("[1 2 3]",1000);

		System.out.println("State dumps");
		runTrial(data,10);
		runTrial(data,10);
		runTrial(data,10);
		runTrial(data,10);
		runTrial(data,10);
	}

	@SuppressWarnings("unused")
	private static void runTrial(String data, long REPS) {
		int len=data.length();
		long start=System.nanoTime();
		ACell warmUp=readStuff(data);
		for (int i=0; i<REPS; i++) {
			ACell v=readStuff(data);
		}
		long end=System.nanoTime();
		
		double bps=(REPS*len)/(0.000000001*(end-start));
		System.out.println("bytes/s: " +Text.toFriendlyNumber((long)bps));
	}

	private static ACell readStuff(String data) {
		return Reader.read(data);
	}
}
