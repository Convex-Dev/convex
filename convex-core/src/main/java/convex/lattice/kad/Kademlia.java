package convex.lattice.kad;

import convex.core.data.AArrayBlob;

public class Kademlia {

	/**
	 * Compute the Kademlia proximity between two blob keys in terms of number of identical bits
	 * @param a First blob key
	 * @param b Second blob key
	 * @return Number of identical leading bits
	 */
	public static int proximity(AArrayBlob a, AArrayBlob b) {
		if (a.count()!=b.count()) throw new IllegalArgumentException("Inconsistent key sizes");
		int bits=a.size()*8;
		
		int sameHex=(int)a.hexMatch(b);
		if ((sameHex*4)>=bits) return bits; // complete match
		
		// Difference must occur at this hex digit
		int va=a.getHexDigit(sameHex);
		int vb=b.getHexDigit(sameHex);
		int prox = (sameHex*4)+hexProximity(va,vb);
		return prox;
	}

	private static int hexProximity(int da, int db) {
		int xor=(da&0x0F)^(db&0x0F);
		return Integer.numberOfLeadingZeros(xor)-28;
	}
	
	/**
	 * Compute the Kademlia distance between two keys as a 64-bit long.
	 * 
	 * This is sufficient for all plausible network sizes at present.
	 */
	public static long distance(AArrayBlob a, AArrayBlob b) {
		try {
		long va=a.longAt(0);
		long vb=b.longAt(0);
		return va^vb;
		} catch (IndexOutOfBoundsException e) {
			throw new IllegalArgumentException("Insufficient bytes for distance check");
		}
	}
}
