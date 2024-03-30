package convex.core.util;

import convex.core.text.Text;

/**
 * Some event counters, for debugging and general metrics
 */
public class Counters {

	public static volatile long sendCount = 0;
	public static volatile long beliefMerge = 0;
	public static volatile long applyBlock = 0;
	
	public static volatile long etchRead = 0;
	public static volatile long etchWrite = 0;
	public static volatile long etchMiss =0;
	
	public static volatile long peerDataReceived=0;
	
	public static String getStats() {
		StringBuffer sb=new StringBuffer();
		
		sb.append("Etch writes:  "+etchWrite+"\n");
		sb.append("Etch reads:   "+etchRead+"\n");
		sb.append("Etch hit(%):  "+Text.toPercentString(100.0*(etchRead-etchMiss)/etchRead)+"\n");

		sb.append("\n");
		sb.append("DATA Rec's:   "+peerDataReceived+"\n");

		
		return sb.toString();
	}
}
