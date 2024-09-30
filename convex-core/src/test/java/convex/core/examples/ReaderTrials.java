package convex.core.examples;

import java.util.List;

import convex.core.State;
import convex.core.data.ACell;
import convex.core.init.Init;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.test.Samples;

/**
 * Test class for juice costs relative to execution time
 */
public class ReaderTrials {

	static State s=Init.createState(List.of(Samples.KEY_PAIR.getAccountKey()));
	static String data=RT.print(s,10000000).toString();
	static int len=data.length();
	
	public static void main(String[] args) {
		System.out.println("Data Length: " + len);
		runTrial();
		runTrial();
		runTrial();
	}

	@SuppressWarnings("unused")
	private static void runTrial() {
		long REPS=10;
		long start=System.currentTimeMillis();
		ACell warmUp=readStuff(data);
		for (int i=0; i<REPS; i++) {
			ACell v=readStuff(data);
		}
		long end=System.currentTimeMillis();
		
		System.out.println((REPS*len)/(0.001*(end-start)));
	}

	private static ACell readStuff(String data) {
		return Reader.read(data);
	}
}
