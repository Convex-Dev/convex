package convex.core.examples;

import java.util.List;

import convex.core.cvm.State;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Refs;
import convex.core.exceptions.BadFormatException;
import convex.core.init.Init;
import convex.core.text.Text;
import convex.test.Samples;

/**
 * Test class for encoding speed
 */
public class EncodingTrials {

	// Data file (printout of a genesis CVM state)
	static State s=Init.createState(List.of(Samples.KEY_PAIR.getAccountKey()));
	
	public static void main(String[] args) throws BadFormatException {
		System.out.println("State dumps");
		runTrial(s,100);
		runTrial(s,100);
		runTrial(s,100);
		runTrial(s,100);
		runTrial(s,100);
		
		System.out.println("State reaps");
		runTrial2(s,100);
		runTrial2(s,100);
		runTrial2(s,100);
		runTrial2(s,100);
		runTrial2(s,100);

	}

	@SuppressWarnings("unused")
	private static void runTrial(ACell data, long REPS) {
		long start=System.nanoTime();
		Blob warmUp=Format.encodeMultiCell(data,true);
		long len=warmUp.count();
		long cellCount=Refs.totalRefCount(s);
		for (int i=0; i<REPS; i++) {
			Blob b=Format.encodeMultiCell(data,true);;
		}
		long end=System.nanoTime();
		
		double bps=(REPS*len)/(0.000000001*(end-start));
		System.out.println("bytes/s: " +Text.toFriendlyNumber((long)bps));
	}
	
	@SuppressWarnings("unused")
	private static void runTrial2(ACell data, long REPS) throws BadFormatException {
		long start=System.nanoTime();
		Blob encoded=Format.encodeMultiCell(data,true);
		State warmUp=Format.decodeMultiCell(encoded);
		long len=encoded.count();
		for (int i=0; i<REPS; i++) {
			State s2=Format.decodeMultiCell(encoded);
		}
		long end=System.nanoTime();
		
		double bps=(REPS*len)/(0.000000001*(end-start));
		System.out.println("bytes/s: " +Text.toFriendlyNumber((long)bps));
	}


}
