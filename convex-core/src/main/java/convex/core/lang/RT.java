package convex.core.lang;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import convex.core.Constants;
import convex.core.cvm.AFn;
import convex.core.cvm.Address;
import convex.core.cvm.transactions.ATransaction;
import convex.core.data.ABlob;
import convex.core.data.ABlobLike;
import convex.core.data.ACell;
import convex.core.data.ACollection;
import convex.core.data.ACountable;
import convex.core.data.ADataStructure;
import convex.core.data.AHashMap;
import convex.core.data.AList;
import convex.core.data.AMap;
import convex.core.data.ASequence;
import convex.core.data.ASet;
import convex.core.data.AString;
import convex.core.data.ASymbolic;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Blobs;
import convex.core.data.Cells;
import convex.core.data.Hash;
import convex.core.data.IAssociative;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Lists;
import convex.core.data.MapEntry;
import convex.core.data.Maps;
import convex.core.data.Ref;
import convex.core.data.Sets;
import convex.core.data.Strings;
import convex.core.data.Symbol;
import convex.core.data.Vectors;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.ANumeric;
import convex.core.data.prim.APrimitive;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMChar;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.data.type.AType;
import convex.core.data.type.Types;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.impl.KeywordFn;
import convex.core.lang.impl.MapFn;
import convex.core.lang.impl.SeqFn;
import convex.core.lang.impl.SetFn;
import convex.core.util.Utils;

/**
 * Static utility class for Runtime functions. Mostly low-level support for Core
 * language capabilities, which will be wrapped as functions in the initial
 * execution environment.
 * 
 * "Low-level programming is good for the programmer's soul." — John Carmack
 */
public class RT {

	/**
	 * Returns true if all elements in an array are equal. Nulls are equal to null
	 * only.
	 * 
	 * @param <T>    Type of values
	 * @param values Array of values
	 * @return True if all values are equal
	 */
	public static <T extends ACell> Boolean allEqual(T[] values) {
		for (int i = 0; i < values.length - 1; i++) {
			if (!Cells.equals(values[i], values[i + 1]))
				return false;
		}
		return true;
	}

	// Numerical comparison functions

	/**
	 * Check if the values passed are a short (length 0 or 1) array of numbers which
	 * is a special case for comparison operations.
	 * 
	 * @return Boolean result, or null if the values are not comparable
	 */
	private static CVMBool checkShortCompare(ACell[] values) {
		int len = values.length;
		if (len == 0)
			return CVMBool.TRUE;
		if (len == 1) {
			if (null == RT.ensureNumber(values[0]))
				return null; // cast failure
			return CVMBool.TRUE;
		}
		return CVMBool.FALSE;
	}

	public static CVMBool eq(ACell[] values) {
		CVMBool check = checkShortCompare(values);
		if (check == null)
			return null;
		if (check==CVMBool.TRUE) return check;
		for (int i = 0; i < values.length - 1; i++) {
			Long comp = RT.compare(values[i], values[i + 1], Long.MAX_VALUE);
			if (comp == null)
				return null; // cast error
			if (comp != 0)
				return CVMBool.FALSE;
		}
		return CVMBool.TRUE;
	}

	public static CVMBool ge(ACell[] values) {
		CVMBool check = checkShortCompare(values);
		if (check == null)
			return null;
		if (check==CVMBool.TRUE) return check;
		for (int i = 0; i < values.length - 1; i++) {
			Long comp = RT.compare(values[i], values[i + 1], Long.MIN_VALUE);
			if (comp == null)
				return null; // cast error
			if (comp < 0)
				return CVMBool.FALSE;
		}
		return CVMBool.TRUE;
	}

	public static CVMBool gt(ACell[] values) {
		CVMBool check = checkShortCompare(values);
		if (check == null)
			return null;
		if (check==CVMBool.TRUE) return check;
		for (int i = 0; i < values.length - 1; i++) {
			Long comp = RT.compare(values[i], values[i + 1], Long.MIN_VALUE);
			if (comp == null)
				return null; // cast error
			if (comp <= 0)
				return CVMBool.FALSE;
		}
		return CVMBool.TRUE;
	}

	public static CVMBool le(ACell[] values) {
		CVMBool check = checkShortCompare(values);
		if (check == null)
			return null;
		if (check==CVMBool.TRUE) return check;
		for (int i = 0; i < values.length - 1; i++) {
			Long comp = RT.compare(values[i], values[i + 1], Long.MAX_VALUE);
			if (comp == null)
				return null; // cast error
			if (comp > 0)
				return CVMBool.FALSE;
		}
		return CVMBool.TRUE;
	}

	public static CVMBool lt(ACell[] values) {
		CVMBool check = checkShortCompare(values);
		if (check == null)
			return null;
		if (check==CVMBool.TRUE) return check;
		for (int i = 0; i < values.length - 1; i++) {
			Long comp = RT.compare(values[i], values[i + 1], Long.MAX_VALUE);
			if (comp == null)
				return null; // cast error
			if (comp >= 0)
				return CVMBool.FALSE;
		}
		return CVMBool.TRUE;
	}
	
	/**
	 * Gets the minimum of a set of numeric values
	 * @param values Arguments for which to compute minimum value
	 * @return minimum value, or null if any argument is non-numeric
	 */
	public static ACell min(ACell[] values) {
		ACell acc=values[0];
		if (!isNumber(acc)) return null;
		for (int i = 1; i < values.length; i++) {
			ACell next=values[i];
			acc=min(acc,next);
		}
		return acc;
	}
	
	/**
	 * Gets the minimum of two numeric values
	 * @param a First value
	 * @param b Second value
	 * @return minimum value, or null if any argument is non-numeric
	 */
	public static ACell min(ACell a, ACell b) {
		if (a==null) return null;
		Long comp=RT.compare(a, b, Long.MIN_VALUE);
		if (comp==null) return null; // bailout on non-numerics
		if (Long.MIN_VALUE==comp) return CVMDouble.NaN;
		if (comp>0) return b;
		return a;
	}
	
	/**
	 * Gets the minimum of a set of numeric values
	 * @param values Arguments for which to compute minimum value
	 * @return minimum value, or null if any argument is non-numeric
	 */
	public static ACell max(ACell[] values) {
		ACell acc=values[0];
		if (!isNumber(acc)) return null;
		for (int i = 1; i < values.length; i++) {
			ACell next=values[i];
			acc=max(acc,next);
		}
		return acc;
	}
	
	/**
	 * Gets the minimum of two numeric values
	 * @param a First value
	 * @param b Second value
	 * @return minimum value, or null if any argument is non-numeric
	 */
	public static ACell max(ACell a, ACell b) {
		if (a==null) return null;
		Long comp=RT.compare(a, b, Long.MIN_VALUE);
		if (comp==null) return null; // bailout on non-numerics
		if (Long.MIN_VALUE==comp) return CVMDouble.NaN;
		if (comp<0) return b;
		return a;
	}
	
	
	/**
	 * Get the target common numeric type for a given set of arguments. - Integers
	 * upcast to Long - Anything else upcasts to Double
	 * 
	 * @param args Argument array
	 * 
	 * @return The target numeric type, or null if there is a non-numeric argument
	 */
	public static Class<?> commonNumericType(ACell[] args) {
		Class<?> highestFound = Long.class;
		for (int i = 0; i < args.length; i++) {
			ACell a = args[i];
			Class<?> klass = numericType(a);
			if (klass == null)
				return null; // break if non-numeric
			if (klass == Double.class)
				highestFound = Double.class;
		}
		return highestFound;
	}

	/**
	 * Finds the first non-numeric value in an array. Used for error reporting.
	 * 
	 * @param args Argument array
	 * @return First non-numeric value, or -1 if not found.
	 */
	public static int findNonNumeric(ACell[] args) {
		for (int i = 0; i < args.length; i++) {
			ACell a = args[i];
			Class<?> klass = numericType(a);
			if (klass == null)
				return i;
		}
		return -1;
	}

	/**
	 * Gets the numeric class of an object
	 * 
	 * @param a Numerical value
	 * @return Long.class or Double.class if cast possible, or null if not numeric.
	 */
	public static Class<?> numericType(ACell a) {
		if (a instanceof ANumeric) {
			return ((ANumeric) a).numericType();
		}
		return null;
	}

	public static ANumeric plus(ACell[] args) {
		int n=args.length;
		if (n==0) return CVMLong.ZERO;
		ANumeric result = RT.ensureNumber(args[0]);
		for (int i = 1; i < args.length; i++) {
			result =result.add(RT.ensureNumber(args[i]));
		}
		return result;
	}

	public static ANumeric minus(ACell[] args) {
		int n = args.length;
		if (n==0) return CVMLong.ZERO;

		ANumeric result = RT.ensureNumber(args[0]);
		if (n==1) return result.negate();
		for (int i = 1; i < n; i++) {
			ANumeric b=RT.ensureNumber(args[i]);
			result =result.sub(b);
		}
		return result;
	}

	public static ANumeric multiply(ACell... args) {
		int n = args.length;
		if (n==0) return CVMLong.ONE;
		ANumeric result = RT.ensureNumber(args[0]);
		for (int i = 1; i < args.length; i++) {
			result =result.multiply(RT.ensureNumber(args[i]));
		}
		return result;
	}


	public static CVMDouble divide(ACell[] args) {
		int n = args.length;
		CVMDouble arg0 = ensureDouble(args[0]);
		if (arg0 == null)
			return null;
		double result = arg0.doubleValue();

		if (n == 1)
			return CVMDouble.create(1.0 / result);
		for (int i = 1; i < args.length; i++) {
			CVMDouble v = ensureDouble(args[i]);
			if (v == null)
				return null;
			result = result / v.doubleValue();
		}
		return CVMDouble.create(result);
	}

	/**
	 * Computes the result of a pow operation. Returns null if a cast fails.
	 * 
	 * @param args Argument array, should be length 2
	 * @return Result of exponentiation
	 */
	public static CVMDouble pow(ACell[] args) {
		CVMDouble a = ensureDouble(args[0]);
		CVMDouble b = ensureDouble(args[1]);
		if ((a == null) || (b == null))
			return null;
		return CVMDouble.create(StrictMath.pow(a.doubleValue(), b.doubleValue()));
	}

	/**
	 * Computes the result of a exp operation. Returns null if a cast fails.
	 * 
	 * @param arg Numeric value
	 * @return Numeric result, or null
	 */
	public static CVMDouble exp(ACell arg) {
		CVMDouble a = ensureDouble(arg);
		if (a == null)
			return null;
		return CVMDouble.create(StrictMath.exp(a.doubleValue()));
	}

	/**
	 * Gets the floor a number after casting to a double. Equivalent to
	 * java.lang.StrictMath.floor(...)
	 * 
	 * @param a Numerical Value
	 * @return The floor of the number, or null if cast fails
	 */
	public static CVMDouble floor(ACell a) {
		CVMDouble d = RT.ensureDouble(a);
		if (d == null)
			return null;
		return CVMDouble.create(StrictMath.floor(d.doubleValue()));
	}

	/**
	 * Gets the ceiling of a number after casting to a Double. Equivalent to
	 * java.lang.StrictMath.ceil(...)
	 * 
	 * @param a Numerical Value
	 * @return The ceiling of the number, or null if cast fails
	 */
	public static CVMDouble ceil(ACell a) {
		CVMDouble d = RT.ensureDouble(a);
		if (d == null)
			return null;
		return CVMDouble.create(StrictMath.ceil(d.doubleValue()));
	}

	/**
	 * Gets the exact positive square root of a number after casting to a Double.
	 * Returns NaN for negative numbers.
	 * 
	 * @param a Numerical Value
	 * @return The square root of the number, or null if cast fails
	 */
	public static CVMDouble sqrt(ACell a) {
		CVMDouble d = RT.ensureDouble(a);
		if (d == null)
			return null;
		return CVMDouble.create(StrictMath.sqrt(d.doubleValue()));
	}

	/**
	 * Compute a SplitMix64 update. Component for splittable PRNG.
	 * 
	 * See: https://xorshift.di.unimi.it/splitmix64.c
	 * 
	 * @param seed Initial SplitMix64 seed
	 * @return Updated SplitMix64 seed
	 */
	public static long splitmix64Update(long seed) {
		return seed + 0x9e3779b97f4a7c15l;
	}
	
	/**
	 * Compute a SplitMix64 value for a given seed. Component for splittabe PRNG
	 * 
	 * See: https://xorshift.di.unimi.it/splitmix64.c
	 * 
	 * @param seed Initial SplitMix64 seed
	 * @return Updated SplitMix64 seed
	 */
	public static long splitmix64Calc(long seed) {
		long x = seed;
		x = (x ^ (x >>> 30)) * 0xbf58476d1ce4e5b9l;
		x = (x ^ (x >>> 27)) * 0x94d049bb133111ebl;
		return x ^ (x >>> 31);
	}


	/**
	 * Converts a long value, treated as unsigned, to a double. Useful for randomness
	 * @param a Long value, treated as unsigned
	 * @return Double value of long
	 */
	public static double doubleFromUnsignedLong(long a) {
		if (a >= 0) {
			return (double) a;
		} else {	
			// For logic in signed case, see Guava's UnsignedLong.doubleValue()
			return (double) ((a >>> 1) | (a & 1)) * 2.0;
		}
	}

	/**
	 * Gets the absolute value of a numeric value. Supports double and long.
	 * 
	 * @param a Numeric CVM value
	 * @return Absolute value, or null if not a numeric value
	 */
	public static APrimitive abs(ACell a) {
		ANumeric x = RT.ensureNumber(a);
		if (x == null)
			return null;
		return x.abs();
	}

	/**
	 * Gets the signum of a numeric value
	 * 
	 * @param a Numeric value
	 * @return value of -1, 0 or 1, NaN is argument is NaN, or null if the argument
	 *         is not numeric
	 */
	public static ACell signum(ACell a) {
		ANumeric x = RT.ensureNumber(a);
		if (x == null)
			return null;
		return x.signum();
	}

	/**
	 * Compares two objects representing numbers numerically.
	 * 
	 * @param a        First numeric value
	 * @param b        Second numeric value
	 * @param nanValue Value to return in case of a NaN result
	 * @return -1 if a is smaller, 1 if a is larger, 0 if a
	 *         equals b, null if either value non-numeric, NaN if either value is NaN
	 */
	public static Long compare(ACell a, ACell b, Long nanValue) {
		Class<?> ca = numericType(a);
		if (ca == null)
			return null;
		Class<?> cb = numericType(b);
		if (cb == null)
			return null;

		if ((ca == Long.class) && (cb == Long.class))
			return (long)((AInteger)a).compareTo((AInteger)b);

		double da = doubleValue(a);
		if (Double.isNaN(da)) return nanValue;
		double db = doubleValue(b);
		if (Double.isNaN(db)) return nanValue;
		if (da == db)
			return 0L;
		if (da < db)
			return -1L;
		if (da > db)
			return 1L;

		return nanValue;
	}

	/**
	 * Converts a CVM value to the standard numeric representation. Result will be
	 * one of:
	 * <ul>
	 * <li>Long for Long</li>
	 * <li>Double for Double</li>
	 * <li>null for any non-numeric value</li>
	 * </ul>
	 * 
	 * @param a Value to convert to numeric representation
	 * @return The number value, or null if cannot be converted
	 */
	public static ANumeric ensureNumber(ACell a) {
		if (a instanceof ANumeric) {
			return (ANumeric) a;
		}
		return null;
	}

	/**
	 * Tests if a Value is a valid numerical value type. 
	 * 
	 * Note: Returns false for null, but true for NaN
	 * 
	 * @param val Value to test
	 * @return True if a number, false otherwise
	 */
	public static boolean isNumber(ACell val) {
		return (val instanceof ANumeric);
	}

	/**
	 * Converts a numerical value to a CVM Double.
	 * 
	 * @param a Value to cast
	 * @return Double value, or null if not convertible
	 */
	public static CVMDouble castDouble(ACell a) {
		if (a instanceof CVMDouble) {
			// Note coercion on non-CVM IEEE754 NaNs
			return ((CVMDouble) a).toDouble();
		}
		
		AInteger l = ensureInteger(a);
		if (l == null)
			return null;
		return l.toDouble();
	}

	/**
	 * Ensures the argument is a CVM Double value.
	 * 
	 * @param a Value to cast
	 * @return CVMDouble value, or null if not convertible
	 */
	public static CVMDouble ensureDouble(ACell a) {
		if (a instanceof ANumeric) {
			ANumeric ap = (ANumeric) a;
			return ap.toDouble();
		}
		return null;
	}

	/**
	 * Converts a numerical value to a CVM Long. Doubles and floats will be
	 * converted if possible. Integers are truncated to last 64 bits
	 * 
	 * @param a Value to cast
	 * @return Long value, or null if not convertible
	 */
	public static CVMLong castLong(ACell a) {
		if (a instanceof CVMLong)
			return (CVMLong) a;
		ANumeric n = ensureNumber(a);
		if (n != null) {
			return ensureLong(n.toInteger());
		}

		if (a instanceof APrimitive) {
			if (a instanceof CVMBool) return null; // disallow boolean -> long cast
			return CVMLong.create(((APrimitive) a).longValue());
		}
		
		if (a instanceof Address) {
			long lv = ((Address) a).longValue();
			return CVMLong.create(lv);
		}

		if (a instanceof ABlob) {
			long lv = ((ABlob) a).longValue();
			return CVMLong.create(lv);
		}

		return null;
	}

	/**
	 * Ensures the argument is a CVM Integer within Long range.
	 * 
	 * @param a Value to cast
	 * @return CVMLong value, or null if not convertible / within long range. 
	 */
	public static CVMLong ensureLong(ACell a) {
		if (a instanceof CVMLong)
			return (CVMLong) a;
		if (a instanceof ANumeric) {
			ANumeric ap = (ANumeric) a;
			return ap.ensureLong();
		}
		return null;
	}
	
	/**
	 * Converts a numerical value to a CVM Integer. Doubles and floats will be
	 * converted if possible.
	 * 
	 * @param a Value to cast
	 * @return Integer value, or null if not convertible
	 */
	public static AInteger castInteger(ACell a) {
		if (a instanceof AInteger)
			return (AInteger) a;
		ANumeric n = ensureNumber(a);
		if (n != null) {
			return n.toInteger();
		}

		if (a instanceof ABlob) {
			return AInteger.create((ABlob) a);
		}

		return castLong(a);
	}

	/**
	 * Ensures the argument is a CVM Integer value.
	 * 
	 * @param a Value to cast
	 * @return AInteger value, or null if not convertible
	 */
	public static AInteger ensureInteger(ACell a) {
		if (a instanceof AInteger) {
			AInteger ap = (AInteger) a;
			return ap;
		}
		return null;
	}

	/**
	 * Explicitly converts a numerical value to a CVM Byte.
	 * 
	 * Doubles and floats will be converted if possible. Takes last byte of Blobs
	 * 
	 * @param a Value to cast
	 * @return Long value, or null if not convertible
	 */
	public static CVMLong castByte(ACell a) {
		if (a instanceof ABlob) {
			ABlob b=(ABlob) a;
			long n=b.count();
			if (n==0) return CVMLong.ZERO;
			return b.get(n-1);
		}
		AInteger l = ensureInteger(a);
		if (l == null)
			return null;
		return CVMLong.forByte((byte) l.longValue());
	}

	private static double doubleValue(ACell a) {
		if (a instanceof APrimitive)
			return ((APrimitive) a).doubleValue();
		throw new IllegalArgumentException("Can't convert to double: " + Utils.getClassName(a));
	}

	/**
	 * Converts any data structure to a vector
	 * 
	 * @param o Object to attempt to convert to a Vector. May wrap a Java array in VectorArray 
	 * @return AVector instance, or null if not convertible
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> AVector<T> vec(Object o) {
		if (o == null)
			return Vectors.empty();
		if (o instanceof ACell)
			return castVector((ACell) o);

		if (o instanceof ACell[]) {
			ACell[] arr = (ACell[])o;
			//return Vectors.create(arr);
			// TODO: this should be faster?
			return Vectors.wrap(arr); 
		}

		if (o instanceof java.util.List)
			return Vectors.create((java.util.List<T>) o);

		return null;
	}

	/**
	 * Converts any countable data structure to a vector. Might be O(n)
	 * 
	 * @param o Value to convert
	 * @return AVector instance, or null if conversion fails
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> AVector<T> castVector(ACell o) {
		if (o == null)
			return Vectors.empty();
		
		// Fast path for existing CVM collections
		if (o instanceof ACollection)
			return vec((ACollection<T>) o);
		
		if (o instanceof ACountable) {
			ACountable<T> ds = (ACountable<T>) o;
			long n = ds.count();
			AVector<T> r = Vectors.empty();
			for (int i = 0; i < n; i++) {
				r = r.conj(ds.get(i));
			}
			return r;
		}
		return null;
	}

	/**
	 * Converts any collection to a set
	 * 
	 * @param o Value to cast
	 * @return Set instance, or null if cast fails
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> ASet<T> castSet(ACell o) {
		if (o == null)
			return Sets.empty();
		if (o instanceof ASet)
			return (ASet<T>) o;
		if (o instanceof ACountable)
			return Sets.create((ACountable<T>) o);
		return null;
	}

	/**
	 * Converts any collection to a vector. Always succeeds, but may have O(n) cost
	 * 
	 * Null values are converted to empty vector (considered as empty sequence)
	 * 
	 * @param coll Collection to convert to a Vector
	 * @return Vector instance
	 */
	public static <T extends ACell> AVector<T> vec(ACollection<T> coll) {
		if (coll == null)
			return Vectors.empty();
		return coll.toVector();
	}

	/**
	 * Converts any collection of cells into a Sequence data structure.
	 * 
	 * Potentially O(n) in size of collection.
	 * 
	 * Nulls are converted to an empty vector.
	 * 
	 * Returns null if conversion is not possible.
	 * 
	 * @param <T> Type of cell in collection
	 * @param o   An object that contains a collection of cells
	 * @return An ASequence instance, or null if the argument cannot be converted to
	 *         a sequence
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> ASequence<T> sequence(ACell o) {
		if (o == null)
			return Vectors.empty();
		if (o instanceof ASequence)
			return (ASequence<T>) o;
		if (o instanceof ACollection)
			return ((ACollection<T>) o).toVector();
		if (o instanceof AMap) {
			// TODO: probably needs fixing! SECURITY
			return sequence(((AMap<?, ?>) o).entryVector());
		}

		return null;
	}

	/**
	 * Ensures argument is a sequence data structure.
	 * 
	 * Nulls are converted to an empty vector.
	 * 
	 * Returns null if conversion is not possible.
	 * 
	 * @param <T> Type of sequence elements
	 * @param o   Value to cast to sequence
	 * @return An ASequence instance, or null if the argument cannot be converted to
	 *         a sequence
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> ASequence<T> ensureSequence(ACell o) {
		if (o == null)
			return Vectors.empty();
		if (o instanceof ASequence)
			return (ASequence<T>) o;
		return null;
	}
	
	/**
	 * Ensures argument is a Vector data structure.
	 * 
	 * Returns null if not a vector is not possible.
	 * 
	 * @param <T> Type of Vector elements
	 * @param o   Value to cast to Vector
	 * @return An AVector instance, or null if the argument is not a Vector
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> AVector<T> ensureVector(ACell o) {
		if (o instanceof AVector)
			return (AVector<T>) o;
		return null;
	}

	/**
	 * Gets the nth element from a sequential collection.
	 * 
	 * Throws an exception if access is out of bounds - caller responsibility to
	 * check bounds first
	 * 
	 * @param <T> Type of element in collection
	 * @param o   Countable Value
	 * @param i   Index of element to get
	 * @return Element from collection at the specified position
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> T nth(ACell o, long i) {
		if (o instanceof ACountable)
			return ((ACountable<T>) o).get(i); // blobs, maps, strings and collections

		throw new ClassCastException("Don't know how to get nth item of type " + RT.getType(o));
	}

	/**
	 * Variant of nth that also handles Java Arrays. Used for destructuring.
	 * 
	 * @param <T> Return type
	 * @param o   Object to check for indexed element
	 * @param i   Index to check
	 * @return Element at specified index
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> T nth(Object o, long i) {
		if (o instanceof ACountable)
			return ((ACountable<T>)o).get(i);

		try {
			if (o.getClass().isArray()) {
				return (T) Array.get(o, Utils.checkedInt(i));
			}
		} catch (IllegalArgumentException e) {
			// can come from checkedInt calls
			throw new IndexOutOfBoundsException(e.getMessage());
		}

		throw new ClassCastException("Can't get nth element from object of class: " + Utils.getClassName(o));
	}

	/**
	 * Gets the count of elements in a collection or Java array. Null is considered
	 * an empty collection.
	 * 
	 * @param o An Object representing a collection of items to be counted
	 * @return The count of elements in the collection, or null if not countable
	 */
	public static Long count(Object o) {
		if (o == null)
			return 0L;
		if (o instanceof ACell)
			return count((ACell) o);
		if (o.getClass().isArray()) {
			return (long) Array.getLength(o);
		}
		return null;
	}

	/**
	 * Gets the count of objects in a collection for destructuring (may be data structure or Java Array)
	 * 
	 * @param o An Object representing a collection of items to be counted
	 * @return The count of elements in the collection, or null if not countable
	 */
	public static Long argumentCount(Object o) {
		if (o == null)
			return 0L;
		if (o instanceof ADataStructure)
			return ((ADataStructure<?>) o).count();
		if (o.getClass().isArray()) {
			return (long) Array.getLength(o);
		}
		return null;
	}

	/**
	 * Gets the count of elements in a countable data structure. Null is considered
	 * an empty collection.
	 * 
	 * @param a Any Cell potentially representing a collection of items to be
	 *          counted
	 * @return The count of elements in the collection, or null if not countable
	 */
	public static Long count(ACell a) {
		// special case: null is considered as empty collection
		if (a == null)
			return 0L;

		if (a instanceof ACountable)
			return ((ACountable<?>) a).count();
		
		return null;
	}

	/**
	 * Converts arguments to an AString representation. Handles:
	 * <ul>
	 * <li>CVM Strings (unchanged)</li>
	 * <li>Blobs (converted to hex)</li>
	 * <li>Numbers (converted to canonical numeric representation)</li>
	 * <li>Other Objects (printed in canonical format)
	 * </ul>
	 * 
	 * @param args Values to convert to String
	 * @return AString value, or null if allowable String length exceeded
	 */
	public static AString str(ACell[] args) {
		// TODO: execution cost limits??
		int n=args.length;
		AString[] strs=new AString[n];
		for (int i=0; i<n; i++) {
			AString s = RT.str(args[i]);
			strs[i]=s;
		}
		return Strings.appendAll(strs);
	}
	
	/**
	 * Prints a cell to a BlobBuilder, up to a specified limit of bytes
	 * @param bb BlobBuilder instance
	 * @param a Cell to print (may be nil)
	 * @param limit Limit of printing
	 * @return True if within limit, false if exceeded (output may still be partially written to BlobBuilder)
	 */
	public static boolean print(BlobBuilder bb, ACell a, long limit) {
		if (a==null) {
			bb.append(Strings.NIL);
			return bb.check(limit);
		} else {
			return a.print(bb, limit);
		}
	}
	
	/**
	 * Prints a cell to a BlobBuilder, up to a specified limit of bytes
	 * WARNING: May return null
	 * @param a Cell to print (may be nil)
	 * @param limit Limit of printing in bytes
	 * @return Printed String, or null if limit exceeded
	 */
	public static AString print(ACell a, long limit) {
		if (a==null) {
			return (limit>=3)?Strings.NIL:null;
		}
		BlobBuilder bb=new BlobBuilder();
		if (!print(bb,a,limit)) return null;
		return bb.getCVMString();
	}
	
	/**
	 * Prints a value to a String as long as the result fits within a given print limit.
	 * WARNING: May return null
	 * @param a Cell value to print
	 * @return Printed String, or null if print limit exceeded
	 */
	public static AString print(ACell a) {
		return print(a,Constants.PRINT_LIMIT);
	}
	
	/**
	 * Prints a value after converting to appropriate CVM type
	 * @param o Any value to print
	 * @return Printed representation of object, or null if print limit exceeded
	 */
	public static AString print(Object o) {
		ACell cell=cvm(o);
		return print(cell);
	}
	/**
	 * Converts a value to a CVM String representation. Required to work for all
	 * valid Cells.
	 * 
	 * @param a Value to convert to a CVM String
	 * @return CVM String representation of object
	 */
	public static AString str(ACell a) {
		if (a == null) {
			return Strings.NIL;
		}
		if (a instanceof AString) {
			return (AString) a;
		}

		if (a.getType()==Types.BLOB) {
			return Strings.create(((ABlob)a).toHexString());
		}
		// TODO: Needs optimisation? toCVMString? print limit?
		AString s = Strings.create(a.toString());
		return s;
	}
	
	/**
	 * Converts a value to a Java String representation
	 * @param a Any CVM value
	 * @return Java String representation. May be "nil".
	 */
	public static String toString(ACell a) {
		return toString(a,Constants.PRINT_LIMIT);
	}
	
	/**
	 * Converts a value to a Java String representation
	 * @param a Any CVM value
	 * @param limit Limit of string printing
	 * @return Java String representation. May be "nil". May include message if print limit exceeded
	 */
	public static String toString(ACell a, long limit) {
		AString s=RT.print(a,limit);
		if (s==null) return Constants.PRINT_EXCEEDED_STRING;
		return s.toString();
	}

	/**
	 * Gets the name from a CVM value. Supports Strings, Keywords and Symbols.
	 *
	 * @param a Value to cast to a name
	 * @return Name of the argument, or null if not Named
	 */
	public static AString name(ACell a) {
		if (a instanceof AString)
			return (AString) a;
		if (a instanceof ASymbolic)
			return ((ASymbolic) a).getName();

		return null;
	}

	/**
	 * Prepends an element to a sequential data structure to create a new list. May
	 * be O(n). The new element will always be in position 0
	 * 
	 * @param <T> Type of elements
	 * @param x   Element to prepend
	 * @param xs  Any sequential object, or null (will be treated as empty sequence)
	 * @return A new list with the cons'ed element at the start
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> AList<T> cons(T x, ASequence<?> xs) {
		if (xs == null)
			return Lists.of(x);
		return ((ASequence<T>) xs).cons(x);
	}

	/**
	 * Prepends two elements to a sequential data structure. The new elements will
	 * always be in position 0 and 1
	 * 
	 * @param <T> Type of elements
	 * @param x   Element to prepend at position 0
	 * @param y   Element to prepend at position 1
	 * @param xs  Any sequential object, or null (will be treated as empty sequence)
	 * @return A new list with the cons'ed elements at the start
	 */
	public static <T extends ACell> AList<T> cons(T x, T y, ACell xs) {
		ASequence<T> nxs = RT.sequence(xs);
		if (xs == null)
			return Lists.of(x, y);
		return nxs.cons(y).cons(x);
	}

	/**
	 * Prepends three elements to a sequential data structure. The new elements will
	 * always be in position 0, 1 and 2
	 * 
	 * @param <T> Type of elements
	 * @param x   Element to prepend at position 0
	 * @param y   Element to prepend at position 1
	 * @param z   Element to prepend at position 2
	 * @param xs  Any sequential object
	 * @return A new list with the cons'ed elements at the start
	 */
	public static <T extends ACell> AList<T> cons(T x, T y, T z, ACell xs) {
		ASequence<T> nxs = RT.sequence(xs);
		return nxs.cons(z).cons(y).cons(x);
	}

	/**
	 * Coerces any object to a data structure type, or returns null if not possible.
	 * Null is converted to an empty vector.
	 * 
	 * @param a value to coerce to collection type.
	 * @return Collection object, or null if coercion failed.
	 */
	@SuppressWarnings("unchecked")
	static <E extends ACell> ADataStructure<E> castDataStructure(ACell a) {
		if (a == null)
			return Vectors.empty();
		if (a instanceof ADataStructure)
			return (ADataStructure<E>) a;
		return null;
	}

	/**
	 * Coerces an argument to a function interface. Certain values e.g. Keywords can
	 * be used / applied in function position.
	 * 
	 * @param <T> Function return type
	 * @param a   Value to cast to a function
	 * @return AFn instance, or null if the argument cannot be coerced to a
	 *         function.
	 * 
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> AFn<T> castFunction(ACell a) {
		if (a instanceof AFn)
			return (AFn<T>) a;
		if (a instanceof AMap)
			return MapFn.wrap((AMap<?, T>) a);
		if (a instanceof ASequence)
			return SeqFn.wrap((ASequence<?>) a);
		if (a instanceof ASet)
			return (AFn<T>) SetFn.wrap((ASet<?>) a);
		if (a instanceof Keyword)
			return KeywordFn.wrap((Keyword) a);
		return null;
	}

	/**
	 * Ensure the argument is a valid CVM function. Returns null otherwise.
	 * 
	 * @param <T> Function return type
	 * @param a   Value to cast to a function
	 * @return IFn instance, or null if the argument cannot be coerced to a
	 *         function.
	 * 
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> AFn<T> ensureFunction(ACell a) {
		if (a instanceof AFn)
			return (AFn<T>) a;
		return null;
	}

	/**
	 * Casts the argument to a valid Address.
	 * 
	 * Handles:
	 * <ul>
	 * <li>Strings, which are interpreted as 16-character hex strings</li>
	 * <li>Addresses, which are returned unchanged</li>
	 * <li>Blobs, which are converted to addresses if and only if they are of the
	 * correct length (8 bytes)</li>
	 * <li>Numeric Longs, which are converted to the equivalent Address</li>
	 * </ul>
	 * 
	 * @param a Value to cast to an Address
	 * @return Address value or null if not castable to a valid address
	 */
	public static Address castAddress(ACell a) {
		if (a instanceof Address)
			return (Address) a;
		if (a instanceof ABlob)
			return Address.create((ABlob) a);
		CVMLong value = RT.ensureLong(a);
		if (value == null)
			return null;
		return Address.create(value.longValue());
	}

	/**
	 * Casts an arbitrary value to an Address
	 * 
	 * @param a Value to cast. Strings or CVM values accepted
	 * @return Address instance, or null if not convertible
	 */
	public static Address toAddress(Object a) {
		if (a instanceof ACell)
			return castAddress((ACell) a);
		if (a instanceof String)
			return Address.parse((String) a);
		return null;
	}

	/**
	 * Ensures the argument is a valid Address.
	 * 
	 * @param a Value to cast
	 * @return Address value or null if not a valid address
	 */
	public static Address ensureAddress(ACell a) {
		if (a instanceof Address)
			return (Address) a;
		return null;
	}

	/**
	 * Implicit cast to an AccountKey. Accepts blobs of correct length
	 * 
	 * @param a Value to cast
	 * @return AccountKey instance, or null if coercion fails
	 */
	public static AccountKey ensureAccountKey(ACell a) {
		if (a == null)
			return null;
		if (a instanceof AccountKey)
			return (AccountKey) a;
		if (a instanceof ABlob) {
			ABlob b = (ABlob) a;
			return AccountKey.create(b);
		}

		return null;
	}

	/**
	 * Coerce to an AccountKey. Accepts strings and blobs of correct length
	 * 
	 * @param a Value to cast
	 * @return AccountKey instance, or null if coercion fails
	 */
	public static AccountKey castAccountKey(ACell a) {
		if (a == null)
			return null;
		if (a instanceof AString)
			return AccountKey.fromHexOrNull((AString) a);
		return ensureAccountKey(a);
	}

	/**
	 * Converts an object to a canonical blob representation. Handles blobs, longs
	 * addresses, hashes and hex strings
	 * 
	 * @param a Value to convert to a Blob
	 * @return Blob value, or null if not convertible to a blob
	 */
	public static ABlob castBlob(ACell a) {
		if (a instanceof AString)
			return Blobs.fromHex((AString)a);

		// handle address, hash, blob instances
		if (a instanceof ABlobLike)
			return ((ABlobLike<?>)a).toBlob();
		
		if (a instanceof AInteger)
			return ((AInteger)a).toBlob();
		if (a instanceof CVMChar)
			return ((CVMChar)a).toUTFBlob();
		
		if (a instanceof CVMBool)
			return ((CVMBool)a).toBlob();
		return null;
	}

	/**
	 * Converts the argument to a non-null Map. Nulls are implicitly converted to
	 * the empty map.
	 * 
	 * @param <K> Type of map keys
	 * @param <V> Type of map values
	 * @param a   Value to cast
	 * @return Map instance, or null if argument cannot be converted to a map
	 * 
	 */
	@SuppressWarnings("unchecked")
	public static <K extends ACell, V extends ACell, R extends AMap<K,V>> R ensureMap(ACell a) {
		if (a == null)
			return (R) Maps.empty();
		if (a instanceof AMap)
			return (R) a;
		return null;
	}

	/**
	 * Gets an element from a data structure using the given key.
	 * 
	 * @param coll Collection to query
	 * @param key  Key to look up in collection
	 * @return Value from collection with the specified key, or null if not found.
	 */
	public static ACell get(ADataStructure<?> coll, ACell key) {
		if (coll == null)
			return null;
		return coll.get(key);
	}

	/**
	 * Gets an element from a data structure using the given key. Returns the
	 * notFound parameter if the data structure does not have the specified key
	 * 
	 * @param coll     Collection to query
	 * @param key      Key to look up in collection
	 * @param notFound Value to return if the lookup failed
	 * @return Value from collection with the specified key, or notFound argument if
	 *         not found.
	 */
	public static ACell get(ADataStructure<?> coll, ACell key, ACell notFound) {
		if (coll == null)
			return notFound;
		return coll.get(key, notFound);
	}

	/**
	 * Converts any CVM value to a boolean value. An value is considered falsey if
	 * null or equal to CVMBool.FALSE, truthy otherwise
	 * 
	 * @param a Object to convert to boolean value
	 * @return true if object is truthy, false otherwise
	 */
	public static boolean bool(ACell a) {
		return !((a == null) || (a == CVMBool.FALSE));
	}
	
	/**
	 * Converts any value to a boolean value. A value is considered falsey if
	 * null, java false or CVMBool.FALSE, truthy otherwise
	 * 
	 * @param a Object to convert to boolean value
	 * @return true if object is truthy, false otherwise
	 */
	public static boolean bool(Object a) {
		if (a==null) return false;
		if (a instanceof Boolean) {
			return ((Boolean)a);
		}
 		return !(a==CVMBool.FALSE);
	}


	/**
	 * Converts an object to a map entry. Handles MapEntries and length 2 Vectors.
	 * 
	 * @param <K> Type of map key
	 * @param <V> Type of map value
	 * @param x   Value to cast
	 * @return MapEntry instance, or null if conversion fails
	 */
	@SuppressWarnings("unchecked")
	public static <K extends ACell, V extends ACell> MapEntry<K, V> ensureMapEntry(ACell x) {
		MapEntry<K, V> me;
		if (x instanceof MapEntry) {
			me = (MapEntry<K, V>) x;
		} else if (x instanceof AVector) {
			AVector<?> v = (AVector<?>) x;
			if (v.count() != 2)
				return null;
			me = MapEntry.fromRefs(v.getRef(0), v.getRef(1));
		} else {
			return null;
		}
		return me;
	}

	/**
	 * Coerces to Hash type. Converts blobs of correct length.
	 * 
	 * @param o Value to cast
	 * @return Hash instance, or null if conversion not possible
	 */
	public static Hash ensureHash(ACell o) {
		if (o instanceof Hash)
			return ((Hash) o);
		if (o instanceof ABlob) {
			ABlob blob = (ABlob) o;
			return Hash.wrap(blob);
		}
		return null;
	}

	/**
	 * Coerces an named value to a Keyword.
	 * 
	 * @param a Value to cast
	 * @return Valid Keyword if correctly constructed, or null if a failure occurs
	 */
	public static Keyword castKeyword(ACell a) {
		if (a instanceof Keyword)
			return (Keyword) a;
		AString name = name(a);
		if (name == null)
			return null;
		Keyword k = Keyword.create(name);
		return k;
	}

	/**
	 * Ensures the argument is a Symbol.
	 * 
	 * @param a Value to cast
	 * @return Symbol if correctly constructed, or null if a failure occurs
	 */
	public static Symbol ensureSymbol(ACell a) {
		if (a instanceof Symbol)
			return (Symbol) a;
		return null;
	}

	/**
	 * Casts to an ADataStructure instance
	 * 
	 * @param <E> Type of data structure element
	 * @param a   Value to cast
	 * @return ADataStructure instance, or null if not a data structure
	 */
	@SuppressWarnings("unchecked")
	public static <E extends ACell> ADataStructure<E> ensureDataStructure(ACell a) {
		if (a instanceof ADataStructure)
			return (ADataStructure<E>) a;
		return null;
	}

	/**
	 * Casts to an ACountable instance
	 * 
	 * @param <E> Type of countable element
	 * @param a   Value to cast
	 * @return ADataStructure instance, or null if not a data structure
	 */
	@SuppressWarnings("unchecked")
	public static <E extends ACell> ACountable<E> ensureCountable(ACell a) {
		if (a instanceof ACountable)
			return (ACountable<E>) a;
		return null;
	}
	
	public static boolean isCountable(ACell val) {
		return (val==null)||(val instanceof ACountable);
	}

	/**
	 * Tests if a value is one of the canonical boolean values 'true' or 'false'
	 * 
	 * @param value Value to test
	 * @return True if the value is a canonical boolean value.
	 */
	public static boolean isBoolean(ACell value) {
		return (value == CVMBool.TRUE) || (value == CVMBool.FALSE);
	}

	/**
	 * Concatenates two sequences. Ignores nulls.
	 * 
	 * @param a First sequence. Will be used to determine the type of the result if
	 *          not null.
	 * @param b Second sequence. Will be the result if the first parameter is null.
	 * @return Concatenated Sequence
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public static ASequence<?> concat(ASequence<?> a, ASequence<?> b) {
		if (a == null)
			return b;
		if (b == null)
			return a;
		return a.concat((ASequence) b);
	}

	/**
	 * Validates an object. Might be a Cell or Ref
	 * 
	 * @param o Object to validate
	 * @throws InvalidDataException For any validation failure
	 */
	public static void validate(Object o) throws InvalidDataException {
		if (o == null)
			return;
		if (o instanceof ACell) {
			((ACell) o).validate();
		} else if (o instanceof Ref) {
			((Ref<?>) o).validate();
		} else {
			throw new InvalidDataException(
					"Data of class" + Utils.getClass(o) + " neither IValidated, canonical nor embedded: ", o);
		}

	}

	/**
	 * Validate a Cell.
	 * 
	 * @param o Object to validate
	 * @throws InvalidDataException For any validation failure
	 */
	public static void validateCell(ACell o) throws InvalidDataException {
		if (o == null)
			return;
		if (o instanceof ACell) {
			((ACell) o).validateCell();
		}
	}

	/**
	 * Associates a key position with a given value in an associative data structure
	 * 
	 * @param coll  Any associative data structure
	 * @param key   Key to update or add
	 * @param value Value to associate with key
	 * @return Updated data structure, or null if implicit cast of key or value to required type fails
	 */
	@SuppressWarnings("unchecked")
	public static <R extends ACell> ADataStructure<R> assoc(ADataStructure<R> coll, ACell key, ACell value) {
		if (coll == null)
			return (ADataStructure<R>) Maps.create(key, value);
		return coll.assoc(key, value);
	}

	/**
	 * Returns a Vector of keys of a Map, or null if the object is not a Map
	 * 
	 * WARNING: Potentially O(n) in size of Map
	 * 
	 * @param a Value to extract keys from (i.e. a Map)
	 * @return Vector of keys in the Map
	 */
	@SuppressWarnings("unchecked")
	public static <R extends ACell> AVector<R> keys(ACell a) {
		if (!(a instanceof AMap))
			return null;
		AMap<R, ACell> m = (AMap<R, ACell>) a;
		return m.getKeys();
	}

	/**
	 * Returns the vector of values of a map, or null if the object is not a map
	 * 
	 * @param a Value to extract values from (i.e. a Map)
	 * @return Vector of values from a map, or null if the object is not a map
	 */
	@SuppressWarnings("unchecked")
	public static <R extends ACell> AVector<R> values(ACell a) {
		if (!(a instanceof AMap))
			return null;
		AMap<ACell, R> m = (AMap<ACell, R>) a;
		return m.values();
	}

	/**
	 * Ensures the argument is an associative data structure instance. A null argument is
	 * considered an empty map.
	 * 
	 * @param o Value to cast
	 * @return IAssociative instance, or null if conversion is not possible
	 */
	public static ADataStructure<?> ensureAssociative(ACell o) {
		if (o == null)
			return Maps.empty();
		if (o instanceof IAssociative)
			return (ADataStructure<?>) o;
		return null;
	}

	/**
	 * Ensures the value is a Set. null is converted to the empty Set.
	 * 
	 * @param <T> Type of Set element
	 * @param a   Value to cast
	 * @return A Set instance, or null if the argument is not a Set
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> ASet<T> ensureSet(ACell a) {
		if (a == null)
			return Sets.empty();
		if (!(a instanceof ASet))
			return null;
		return (ASet<T>) a;
	}

	/**
	 * Casts the argument to a hashmap. null is converted to the empty HashMap.
	 * 
	 * @param <K> Type of keys
	 * @param <V> Type of values
	 * @param a   Any object
	 * @return AHashMap instance, or null if not a hash map
	 */
	@SuppressWarnings("unchecked")
	public static <K extends ACell, V extends ACell> AHashMap<K, V> ensureHashMap(ACell a) {
		if (a == null)
			return Maps.empty();
		if (a instanceof AHashMap)
			return (AHashMap<K, V>) a;
		return null;
	}
	
	@SuppressWarnings("unchecked")
	public static <K extends ABlobLike<?>, V extends ACell> Index<K, V> ensureIndex(ACell a) {
		if (a instanceof Index)
			return (Index<K, V>) a;
		return null;
	}

	/**
	 * Implicitly casts the argument to a Blob
	 * 
	 * @param a Value to cast to Blob
	 * @return Blob instance, or null if cast fails
	 */
	public static ABlob ensureBlob(ACell a) {
		if (a instanceof ABlob) {
			ABlob b= ((ABlob) a);
			if (b.getType()!=Types.BLOB) return null; // catch specialised Blobs
			return b;
		}
		return null;
	}
	
	@SuppressWarnings("unchecked")
	public static <V extends ACell> ABlobLike<V> ensureBlobLike(ACell a) {
		if (a instanceof ABlobLike) {
			ABlobLike<V> b= ((ABlobLike<V>) a);
			return b;
		}
		return null;
	}

	/**
	 * Ensures the argument is a CVM String
	 * 
	 * @param a Value to cast to a String
	 * @return AString instance, or null if argument is not a String
	 */
	public static AString ensureString(ACell a) {
		if (a instanceof AString)
			return ((AString) a);
		return null;
	}

	public static boolean isValidAmount(long amount) {
		return ((amount >= 0) && (amount <= Constants.MAX_SUPPLY));
	}

	/**
	 * Converts a Java value to a CVM type.
	 * 
	 * @param o Any Java Object
	 * @return Valid CVM type
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> T cvm(Object o) {
		if (o == null)
			return null;
		if (o instanceof ACell)
			return ((T) o);
		if (o instanceof String)
			return (T) Strings.create((String) o);
		if (o instanceof Double)
			return (T) CVMDouble.create(((Double) o));
		if (o instanceof Number)
			return (T) CVMLong.create(((Number) o).longValue());
		if (o instanceof Character)
			return (T) CVMChar.create((Character) o);
		if (o instanceof Boolean)
			return (T) CVMBool.create((Boolean) o);
		if (o instanceof List) {
			List<?> l=(List<?>)o;
			AVector<?> v=Vectors.empty(); 
			for (Object val: l) {
				v=v.conj(cvm(val));
			}
			return (T)v;
		}
		if (o instanceof Map) {
			Map<?,?> m= (Map<?,?>) o;
			AMap<ACell,ACell> cm=Maps.empty();
			for (Map.Entry<?, ?> me: m.entrySet()) {
				Object k=me.getKey();
				Object v=me.getValue();
				ACell cvmk=cvm(k);
				ACell cvmv=cvm(v);
				cm=cm.assoc(cvmk, cvmv);
			}
			return (T) cm;
		}
		Class<?> klass=o.getClass();
		if (klass.isArray()) {
			AVector<?> r=Vectors.empty();
			int n=Array.getLength(o);
			for (int i=0; i<n; i++) {
				Object elem=Array.get(o, i);
				ACell v=RT.cvm(elem);
				r=r.conj(v);
			}
			return (T) r;
		}
		
		throw new IllegalArgumentException("Can't convert to CVM type with class: " + Utils.getClassName(o));
	}

	/**
	 * Converts a CVM value to equivalent JVM value
	 * 
	 * @param o Value to convert to JVM type
	 * @return Java value, or unchanged input
	 */
	@SuppressWarnings("unchecked")
	public static <T> T jvm(ACell o) {
		if (o instanceof AString)
			return (T) o.toString();
		if (o instanceof CVMLong)
			return (T) (Long) ((CVMLong) o).longValue();
		if (o instanceof CVMDouble)
			return (T) (Double) ((CVMDouble) o).doubleValue();
		if (o instanceof CVMBool)
			return (T) (Boolean) ((CVMBool) o).booleanValue();
		if (o instanceof CVMChar)
			return (T) (Character) ((CVMChar) o).charValue();
		return (T) o;
	}
	
	/**
	 * Converts a CVM value to equivalent JSON value as expressed in equivalent JVM types.
	 * 
	 * Note some special one-way conversions that are required because JSON is not 
	 * sufficiently expressive for all CVM types:
	 * - Address becomes a Number (Long type)
	 * - Lists and Vectors both become an Array (Java List type)
	 * - Characters become a String
	 * - Blobs become a hex string representation '0x....'
	 * 
	 * @param o Value to convert to JSON value object
	 * @return Java Object which represents JSON value
	 */
	@SuppressWarnings("unchecked")
	public static <T> T json(ACell o) {
		if (o==null) return null;
		if (o instanceof CVMLong)
			return (T) (Long) ((CVMLong) o).longValue();
		if (o instanceof CVMDouble)
			return (T) (Double) ((CVMDouble) o).doubleValue();
		if (o instanceof CVMBool)
			return (T) (Boolean) ((CVMBool) o).booleanValue();
		if (o instanceof CVMChar)
			return (T) ((CVMChar) o).toString();
		if (o instanceof Address)
			return (T) (Long)((Address) o).longValue();
		if (o instanceof AMap) {
			AMap<?,?> m= (AMap<?,?>)o;
			return (T)jsonMap(m);
		}
		if (o instanceof ASequence) {
			ASequence<?> seq= (ASequence<?>)o;
			long n=seq.count();
			ArrayList<Object> list=new ArrayList<>();
			for (long i=0; i<n; i++) {
				ACell cvmv=seq.get(i);
				Object v=json(cvmv);
				list.add(v);
			}
			return (T) list;
		}

		return (T) o.toString();
	}
	
	/**
	 * Converts a CVM Map to a JSON representation
	 * @param m Map to convert to JSON representation
	 * @return Java value which represents JSON object
	 */
	public static HashMap<String,Object> jsonMap(AMap<?,?> m) {
		int n=m.size();
		HashMap<String,Object> hm=new HashMap<String,Object>(n);
		for (long i=0; i<n; i++) {
			MapEntry<?,?> me=m.entryAt(i);
			ACell k=me.getKey();
			String sk=jsonKey(k);
			Object v=json(me.getValue());
			hm.put(sk, v);
		}
		return hm;
	}

	/**
	 * Gets a String from a value suitable for use as a JSON map key
	 * @param k Value to convert to a JSON key
	 * @return String usable as JSON key
	 */
	public static String jsonKey(ACell k) {
		if (k instanceof AString) return k.toString();
		if (k instanceof Keyword) return ((Keyword)k).getName().toString();
		return RT.toString(k);
	}


	/**
	 * Get the runtime Type of any CVM value
	 * 
	 * @param a Any CVM value
	 * @return Type of CVM value
	 */
	public static AType getType(ACell a) {
		if (a == null)
			return Types.NIL;
		return a.getType();
	}

	public static boolean isNaN(ACell val) {
		return CVMDouble.NaN.equals(val);
	}

	/**
	 * Implicitly casts argument to a CVM Character
	 * @param a Value to cast
	 * @return CVMChar instance, or null if not implicitly castable
	 */
	public static CVMChar ensureChar(ACell a) {
		if (a instanceof CVMChar) return (CVMChar)a;
		if (a instanceof CVMLong) return CVMChar.create(((CVMLong)a).longValue());
		if (a instanceof AString) {
			AString s=(AString) a;
			long n=s.count();
			if ((n==0)||(n>CVMChar.MAX_UTF_BYTES)) return null;
			long cv=s.charAt(0);
			if (cv<0) return null;
			if (n!=CVMChar.utfLength(cv)) return null;
			
			return CVMChar.create(cv);
		}
		return null;
	}

	/**
	 * Gets a callable Address from a cell value. Handles regular Addresses and scoped call targets
	 * @param a Value to extract Address from
	 * @return Address of callable target, or null if not a valid call target
	 */
	public static Address callableAddress(ACell a) {
		Address addr=RT.ensureAddress(a);
		if (addr == null) {
			if (a instanceof AVector) {
				AVector<?> v=(AVector<?>)a;
				if (v.count()!=2) return null;
				addr=RT.ensureAddress(v.get(0));
			} 
		}
		return addr;
	}

	/**
	 * Casts to a transaction record, or null if not castable
	 * @param maybeTx Cell which should be a transaction
	 * @return Transaction value, or null if not a transaction
	 */
	public static ATransaction ensureTransaction(ACell maybeTx) {
		if (maybeTx instanceof ATransaction) return (ATransaction)maybeTx;
		return null;
	}

	public static boolean printCAD3(BlobBuilder sb, long limit, ACell cell) {
		sb.append((byte)'#');
		sb.append((byte)'[');
		sb.appendCAD3Hex(Cells.getEncoding(cell));
		sb.append((byte)']');
		return sb.check(limit);
	}

	public static long[] toLongArray(AVector<?> v) {
		int n=v.size();
		long[] result=new long[n];
		for (int i=0; i<n; i++) {
			result[i]=RT.ensureLong(v.get(i)).longValue();
		}
		return result;
	}




}
