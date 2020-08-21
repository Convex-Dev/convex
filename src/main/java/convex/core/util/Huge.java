package convex.core.util;

import convex.core.exceptions.TODOException;

/**
 * A 128-bit integer
 */
public class Huge {
	
	// Some useful constants
	public static final Huge ZERO = create(0L);
	public static final Huge ONE = create(1L);
	
	public final long hi;
	public final long lo;
	
	private Huge(long hi,long lo) {
		this.hi=hi;
		this.lo=lo;
		
	}
	
	/**
	 * Creates a new Huge by sign extending a long to 128 bits
	 * @param a Any signed 64-bit long value
	 * @return
	 */
	public static Huge create(long a) {
		return new Huge((a>=0)?0:-1,a);
	}

	/**
	 * Creates a new Huge by multiplying two signed longs
	 * @param a Any signed 64-bit long value
	 * @param b Any signed 64-bit long value
	 * @return
	 */
	public static Huge multiply(long a, long b) {
		long hi=Math.multiplyHigh(a, b);
		return new Huge(hi,a*b);
	}
	
	/**
	 * Creates a new Huge by multiplying a Huge with a signed long
	 * @param a Any signed 128-bit Huge value
	 * @param b Any signed 64-bit long value
	 * @return
	 */
	public static Huge multiply(Huge a, long b) {
		long carry=Math.multiplyHigh(a.lo, b);
		return new Huge(carry+a.hi*b,a.lo*b);
	}

	/**
	 * Creates a Huge by adding two signed longs
	 * @param a Any signed 64-bit long value
	 * @param b Any signed 64-bit long value
	 * @return
	 */
	public static Huge add(long a, long b) {
		long carry = UMath.unsignedAddCarry(a,b);
		long signSum = ((a<0)?-1:0) + ((b<0)?-1:0);
		return new Huge(carry+signSum,a+b);
	}

	/**
	 * Creates a Huge by adding a long value to this Huge
	 * @param b Any signed 64-bit long value
	 * @return
	 */
	public Huge add(long b) {
		long carry = UMath.unsignedAddCarry(lo,b);
		long sign = ((b<0)?-1:0);
		
		return new Huge(hi+sign+carry,lo+b);
	}
	

	/**
	 * Creates a Huge by adding another Huge
	 * @param b Any Huge value
	 * @return
	 */
	public Huge add(Huge b) {
		long carry = UMath.unsignedAddCarry(lo,b.lo);
		return new Huge(hi+b.hi+carry,lo+b.lo);
	}
	
	@Override
	public boolean equals(Object a) {
		if (a instanceof Huge) return equals((Huge)a);
		return false;
	}
	
	/**
	 * Tests if this Huge is equal to another Huge
	 * @param a Another Huge instance (must not be null)
	 * @return true is the Huge values are equal, false otherwise
	 */
	public boolean equals(Huge a) {
		return (lo==a.lo)&&(hi==a.hi);
	}
	
	@Override
	public String toString() {
		return "#huge 0x"+Utils.toHexString(hi)+Utils.toHexString(lo);
	}

	public Huge sub(Huge b) {
		return add(b.negate());
	}

	/**
	 * Negates this Huge value
	 * @return
	 */
	public Huge negate() {
		return new Huge(-hi-((lo!=0L)?1L:0L),-lo);
	}

	public Huge mul(Huge b) {
		throw new TODOException();
		// Broken because of carrying
		// return new Huge((hi*b.lo) + (lo*b.hi) + UMath.multiplyHigh(lo, b.lo),lo*b.lo);
	}
}
