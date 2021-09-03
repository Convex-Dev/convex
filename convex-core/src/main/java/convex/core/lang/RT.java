package convex.core.lang;

import java.lang.reflect.Array;
import java.util.function.BiFunction;

import convex.core.Constants;
import convex.core.data.ABlob;
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
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Address;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.Hash;
import convex.core.data.INumeric;
import convex.core.data.Keyword;
import convex.core.data.Lists;
import convex.core.data.MapEntry;
import convex.core.data.Maps;
import convex.core.data.Ref;
import convex.core.data.Sets;
import convex.core.data.Strings;
import convex.core.data.Symbol;
import convex.core.data.Vectors;
import convex.core.data.prim.APrimitive;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMByte;
import convex.core.data.prim.CVMChar;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.data.type.AType;
import convex.core.data.type.Types;
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
	 * @param <T> Type of values
	 * @param values Array of values
	 * @return True if all values are equal
	 */
	public static <T extends ACell> Boolean allEqual(T[] values) {
		for (int i = 0; i < values.length - 1; i++) {
			if (!Utils.equals(values[i], values[i + 1])) return false;
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
	private static Boolean checkShortCompare(ACell[] values) {
		int len = values.length;
		if (len == 0) return true;
		if (len == 1) {
			if (null == RT.ensureNumber(values[0])) return null; // cast failure
			return true;
		}
		return false;
	}

	public static Boolean eq(ACell[] values) {
		Boolean check = checkShortCompare(values);
		if (check == null) return null;
		if (check) return true;
		for (int i = 0; i < values.length - 1; i++) {
			Long comp = RT.compare(values[i], values[i + 1],Long.MAX_VALUE);
			if (comp == null) return null; // cast error
			if (comp != 0) return false;
		}
		return true;
	}

	public static Boolean ge(ACell[] values) {
		Boolean check = checkShortCompare(values);
		if (check == null) return null;
		if (check) return true;
		for (int i = 0; i < values.length - 1; i++) {
			Long comp = RT.compare(values[i], values[i + 1],Long.MIN_VALUE);
			if (comp == null) return null; // cast error
			if (comp < 0) return false;
		}
		return true;
	}

	public static Boolean gt(ACell[] values) {
		Boolean check = checkShortCompare(values);
		if (check == null) return null;
		if (check) return true;
		for (int i = 0; i < values.length - 1; i++) {
			Long comp = RT.compare(values[i], values[i + 1],Long.MIN_VALUE);
			if (comp == null) return null; // cast error
			if (comp <= 0) return false;
		}
		return true;
	}

	public static Boolean le(ACell[] values) {
		Boolean check = checkShortCompare(values);
		if (check == null) return null;
		if (check) return true;
		for (int i = 0; i < values.length - 1; i++) {
			Long comp = RT.compare(values[i], values[i + 1],Long.MAX_VALUE);
			if (comp == null) return null; // cast error
			if (comp > 0) return false;
		}
		return true;
	}

	public static Boolean lt(ACell[] values) {
		Boolean check = checkShortCompare(values);
		if (check == null) return null;
		if (check) return true;
		for (int i = 0; i < values.length - 1; i++) {
			Long comp = RT.compare(values[i], values[i + 1],Long.MAX_VALUE);
			if (comp == null) return null; // cast error
			if (comp >= 0) return false;
		}
		return true;
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
		Class<?> highestFound=Long.class;
		for (int i = 0; i < args.length; i++) {
			ACell a = args[i];
			Class<?> klass = numericType(a);
			if (klass == null) return null; // break if non-numeric
			if (klass == Double.class) highestFound=Double.class;
		}
		return highestFound;
	}

	/**
	 * Finds the first non-numeric value in an array. Used for error reporting.
	 * 
	 * @param args Argument array
	 * @return First non-numeric value, or null if not found.
	 */
	public static int findNonNumeric(ACell[] args) {
		for (int i = 0; i < args.length; i++) {
			ACell a = args[i];
			Class<?> klass = numericType(a);
			if (klass == null) return i;
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
		if (a instanceof INumeric) {
			return ((INumeric)a).numericType();
		}
		return null;
	}

	public static APrimitive plus(ACell[] args) {
		Class<?> type = commonNumericType(args);
		if (type == null) return null;
		if (type == Double.class) return plusDouble(args);
		long result = 0;
		for (int i = 0; i < args.length; i++) {
			result += RT.longValue(args[i]);
		}
		return CVMLong.create(result);
	}

	public static CVMDouble plusDouble(ACell[] args) {
		double result = 0;
		for (int i = 0; i < args.length; i++) {
			result += RT.doubleValue(args[i]);
		}
		return CVMDouble.create(result);
	}

	public static APrimitive minus(ACell[] args) {
		Class<?> type = commonNumericType(args);
		if (type == null) return null;
		if (type == Double.class) return minusDouble(args);
		int n = args.length;
		long result = longValue(args[0]);
		if (n == 1) result= -result;
		for (int i = 1; i < n; i++) {
			result -= RT.longValue(args[i]);
		}
		return CVMLong.create(result);
	}

	public static APrimitive minusDouble(ACell[] args) {
		int n = args.length;
		double result = doubleValue(args[0]);
		if (n == 1) result= -result;
		for (int i = 1; i < args.length; i++) {
			result -= RT.doubleValue(args[i]);
		}
		return CVMDouble.create(result);
	}

	public static APrimitive times(ACell[] args) {
		Class<?> type = commonNumericType(args);
		if (type == null) return null;
		if (type == Double.class) return timesDouble(args);
		long result = 1;
		for (int i = 0; i < args.length; i++) {
			result *= RT.longValue(args[i]);
		}
		return CVMLong.create(result);
	}

	public static APrimitive timesDouble(ACell[] args) {
		double result = 1;
		for (int i = 0; i < args.length; i++) {
			result *= RT.doubleValue(args[i]);
		}
		return CVMDouble.create(result);
	}

	public static CVMDouble divide(ACell[] args) {
		int n = args.length;
		CVMDouble arg0 = ensureDouble(args[0]);
		if (arg0 == null) return null;
		double result=arg0.doubleValue();

		if (n == 1) return CVMDouble.create(1.0 / result);
		for (int i = 1; i < args.length; i++) {
			CVMDouble v = ensureDouble(args[i]);
			if (v == null) return null;
			result = result / v.doubleValue();
		}
		return CVMDouble.create(result);
	}

	/**
	 * Computes the result of a pow operation. Returns null if a cast fails.
	 * @param args Argument array, should be length 2
	 * @return Result of exponentiation
	 */
	public static CVMDouble pow(ACell[] args) {
		CVMDouble a = ensureDouble(args[0]);
		CVMDouble b = ensureDouble(args[1]);
		if ((a==null)||(b==null)) return null;
		return CVMDouble.create(StrictMath.pow(a.doubleValue(), b.doubleValue()));
	}

	/**
	 * Computes the result of a exp operation. Returns null if a cast fails.
	 * @param arg Numeric value
	 * @return Numeric result, or null
	 */	
	public static CVMDouble exp(ACell arg) {
		CVMDouble a = ensureDouble(arg);
		if (a==null) return null;
		return CVMDouble.create(StrictMath.exp(a.doubleValue()));
	}
	
	/**
	 * Gets the floor a number after casting to a double. Equivalent to java.lang.StrictMath.floor(...)
	 * 
	 * @param a Numerical Value
	 * @return The floor of the number, or null if cast fails
	 */
	public static CVMDouble floor(ACell a) {
		CVMDouble d = RT.ensureDouble(a);
		if (d == null) return null;
		return CVMDouble.create(StrictMath.floor(d.doubleValue()));
	}
	
	/**
	 * Gets the ceiling a number after casting to a double. Equivalent to java.lang.StrictMath.ceil(...)
	 * 
	 * @param a Numerical Value
	 * @return The ceiling of the number, or null if cast fails
	 */
	public static CVMDouble ceil(ACell a) {
		CVMDouble d = RT.ensureDouble(a);
		if (d == null) return null;
		return CVMDouble.create(StrictMath.ceil(d.doubleValue()));
	}

	/**
	 * Gets the exact positive square root of a number after casting to a double.
	 * Returns NaN for negative numbers.
	 * 
	 * @param a Numerical Value
	 * @return The square root of the number, or null if cast fails
	 */
	public static CVMDouble sqrt(ACell a) {
		CVMDouble d = RT.ensureDouble(a);
		if (d == null) return null;
		return CVMDouble.create(StrictMath.sqrt(d.doubleValue()));
	}
	
	/**
	 * Gets the absolute value of a numeric value. Supports double and long.
	 * 
	 * @param a Numeric CVM value
	 * @return Absolute value, or null if not a numeric value
	 */
	public static APrimitive abs(ACell a) {
		INumeric x=RT.ensureNumber(a);
		if (x==null) return null;
		if (x instanceof CVMLong) return CVMLong.create( Math.abs(((CVMLong) x).longValue()));
		return CVMDouble.create(Math.abs(x.toDouble().doubleValue()));
	}
	
	/**
	 * Gets the signum of a numeric value
	 * 
	 * @param a Numeric value
	 * @return value of -1, 0 or 1, NaN is argument is NaN, or null if the argument is not numeric
	 */
	public static ACell signum(ACell a) {
		INumeric x=RT.ensureNumber(a);
		if (x==null) return null;
		return x.signum();
	}

	/**
	 * Compares two objects representing numbers numerically.
	 * 
	 * @param a First numeric value
	 * @param b Second numeric value
	 * @param nanValue Value to return in case of a NaN result
	 * @return Less than 0 if a is smaller, greater than 0 if a is larger, 0 if a
	 *         equals b
	 */
	public static Long compare(ACell a, ACell b,Long nanValue) {
		Class<?> ca = numericType(a);
		if (ca == null) return null;
		Class<?> cb = numericType(b);
		if (cb == null) return null;

		if ((ca == Long.class) && (cb == Long.class)) return RT.compare(longValue(a), longValue(b));

		double da=doubleValue(a);		
		double db=doubleValue(b);
		if (da==db) return 0L;
		if (da<db) return -1L;
		if (da>db) return 1L;

		return nanValue;
	}

	/**
	 * Compares two long values numerically, according to Java primitive
	 * comparisons.
	 * 
	 * @param a First number
	 * @param b Second number
	 * @return -1 if a is less than b, 1 if greater, 0 is they are equal
	 */
	public static long compare(long a, long b) {
		if (a < b) return -1;
		if (a > b) return 1;
		return 0;
	}

	/**
	 * Converts a CVM value to the standard numeric representation. Result will be one of: 
	 * <ul> 
	 * <li>Long for Byte, Long</li>
	 * <li>Double for Double</li>
	 * <li>null for any non-numeric value</li>
	 * </ul>
	 * 
	 * @param a Value to convert to numeric representation
	 * @return The number value, or null if cannot be converted
	 */
	public static INumeric ensureNumber(ACell a) {
		if (a == null) return null;
		
		if (a instanceof INumeric) {
			return ((INumeric)a).toStandardNumber();
		}

		return null;
	}
	
	/**
	 * Tests if a Value is a valid numerical value
	 * @param val Value to test
	 * @return True if a number, false otherwise
	 */
	public static boolean isNumber(ACell val) {
		return (val instanceof INumeric);
	}

	/**
	 * Increments a Long value
	 * @param x Value to increment
	 * @return Long Value, or null if conversion fails
	 */
	public static CVMLong inc(ACell x) {
		CVMLong n = ensureLong(x);
		if (n == null) return null;
		return CVMLong.create(n.longValue() + 1L);
	}

	/**
	 * Decrements a Long value
	 * @param x Value to decrement
	 * @return Long Value, or null if conversion fails
	 */
	public static CVMLong dec(ACell x) {
		CVMLong n = ensureLong(x);
		if (n == null) return null;
		return CVMLong.create(n.longValue() - 1L);
	}

	/**
	 * Converts a numerical value to a CVM Double. 
	 * @param a Value to cast
	 * @return Double value, or null if not convertible
	 */
	public static CVMDouble castDouble(ACell a) {
		if (a instanceof CVMDouble) return (CVMDouble) a;
				
		CVMLong l=castLong(a);
		if (l==null) return null;
		return l.toDouble();
	}
	
	/**
	 * Ensures the argument is a CVM Long value. 
	 * @param a Value to cast
	 * @return CVMDouble value, or null if not convertible
	 */
	public static CVMDouble ensureDouble(ACell a) {
		if (a instanceof INumeric) {
			INumeric ap=(INumeric)a;
			return ap.toDouble();
		}
		return null;
	}
	
	/**
	 * Converts a numerical value to a CVM Long. Doubles and floats will be converted if possible.
	 * @param a Value to cast
	 * @return Long value, or null if not convertible
	 */
	public static CVMLong castLong(ACell a) {
		if (a instanceof CVMLong) return (CVMLong) a;
		INumeric n = ensureNumber(a);
		if (n != null) {
			return n.toLong();
		};
		
		if (a instanceof APrimitive) {
			return CVMLong.create(((APrimitive)a).longValue());
		}
		
		if (a instanceof ABlob) {
			long lv=((ABlob)a).toLong();
			return CVMLong.create(lv);
		}
		
		return null;
	}
	
	/**
	 * Ensures the argument is a CVM Long value. 
	 * @param a Value to cast
	 * @return CVMLong value, or null if not convertible
	 */
	public static CVMLong ensureLong(ACell a) {
		if (a instanceof CVMLong) return (CVMLong) a;
		if (a instanceof INumeric) {
			INumeric ap=(INumeric)a;
			if (ap.numericType()==Long.class) return ap.toLong();
		}
		return null;
	}
	
	/**
	 * Explicitly converts a numerical value to a CVM Byte. 
	 * 
	 * Doubles and floats will be converted if possible.
	 * 
	 * @param a Value to cast
	 * @return Long value, or null if not convertible
	 */
	public static CVMByte castByte(ACell a) {
		if (a instanceof CVMByte) return (CVMByte) a;
		CVMLong l=castLong(a);
		if (l == null) return null;
		return CVMByte.create((byte)l.longValue());
	}

	/**
	 * Casts a value to a Character
	 * @param a Value to cast
	 * @return CVMChar value, or null if cast fails
	 */
	public static CVMChar toCharacter(ACell a) {
		if (a instanceof CVMChar) return (CVMChar) a;
		CVMLong l=castLong(a);
		if (l == null) return null;
		return CVMChar.create(l.longValue());
	}

	private static long longValue(ACell a) {
		if (a instanceof APrimitive) return ((APrimitive) a).longValue();
		throw new IllegalArgumentException("Can't convert to long: " + Utils.getClassName(a));
	}

	private static double doubleValue(ACell a) {
		if (a instanceof APrimitive) return ((APrimitive) a).doubleValue();
		throw new IllegalArgumentException("Can't convert to double: " + Utils.getClassName(a));
	}
	
	/**
	 * Converts any data structure to a vector
	 * 
	 * @param o Object to attemptto convert to a Vector
	 * @return AVector instance, or null if not convertible
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> AVector<T> vec(Object o) {
		if (o==null) return Vectors.empty();
		if (o instanceof ACell) return castVector((ACell) o);
		
		if (o.getClass().isArray()) {
			ACell[] arr = Utils.toCellArray(o);
			return Vectors.create(arr);
		}

		if (o instanceof java.util.List) return Vectors.create((java.util.List<T>) o);

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
		if (o == null) return Vectors.empty();
		if (o instanceof ACollection) return vec((ACollection<T>) o);
		if (o instanceof ACountable) {
			ACountable<T> ds=(ACountable<T>) o;
			long n=ds.count();
			AVector<T> r=Vectors.empty();
			for (int i=0; i<n; i++) {
				r=r.conj(ds.get(i));
			}
			return r;
 		}
		return null;
	}

	/**
	 * Converts any collection to a set
	 * @param o Value to cast
	 * @return Set instance, or null if cast fails
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> ASet<T> castSet(ACell o) {
		if (o == null) return Sets.empty();
		if (o instanceof ASet) return (ASet<T>) o;
		if (o instanceof ADataStructure) return Sets.create((ADataStructure<T>) o);
		return null;
	}

	/**
	 * Converts any collection to a vector. Always succeeds, but may have O(n) cost
	 * 
	 * Null values are converted to empty vector (considered as empty sequence)
	 * @param coll Collection to convert to a Vector
	 * @return Vector instance
	 */
	public static <T extends ACell> AVector<T> vec(ACollection<T> coll) {
		if (coll == null) return Vectors.empty();
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
	 * @param o An object that contains a collection of cells
	 * @return An ASequence instance, or null if the argument cannot be converted to
	 *         a sequence
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> ASequence<T> sequence(ACell o) {
		if (o == null) return Vectors.empty();
		if (o instanceof ASequence) return (ASequence<T>) o;
		if (o instanceof ACollection) return ((ACollection<T>) o).toVector();
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
	 * @param o Value to cast to sequence
	 * @return An ASequence instance, or null if the argument cannot be converted to
	 *         a sequence
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> ASequence<T> ensureSequence(ACell o) {
		if (o == null) return Vectors.empty();
		if (o instanceof ASequence) return (ASequence<T>) o;
		return null;
	}

	/**
	 * Gets the nth element from a sequential collection.
	 * 
	 * Throws an exception if access is out of bounds - caller responsibility to check bounds first
	 * 
	 * @param <T> Type of element in collection
	 * @param o Countable Value
	 * @param i Index of element to get
	 * @return Element from collection at the specified position
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> T nth(ACell o, long i) {
		// special case, we treat nil as empty sequence
		if (o == null) throw new IndexOutOfBoundsException("Can't get nth element from null");

		if (o instanceof ACountable) return ((ACountable<T>) o).get(i); // blobs, maps and collections

		throw new ClassCastException("Don't know how to get nth item of type "+RT.getType(o));
	}
	
	/**
	 * Variant of nth that also handles Java Arrays. Used for destructuring.
	 * 
	 * @param <T> Return type
	 * @param o Object to check for indexed element
	 * @param i Index to check
	 * @return Element at specified index
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> T nth(Object o, long i) {
		if (o instanceof ACell) return nth((ACell)o,i);

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
	 * Gets the count of elements in a collection or Java array. Null is considered an empty collection.
	 * 
	 * @param o An Object representing a collection of items to be counted
	 * @return The count of elements in the collection, or null if not countable
	 */
	public static Long count(Object o) {
		if (o == null) return 0L;
		if (o instanceof ACell) return count((ACell)o);
		if (o.getClass().isArray()) {
			return (long) Array.getLength(o);
		}
		return null;
	}

	/**
	 * Gets the count of elements in a countable data structure. Null is considered an empty collection.
	 * 
	 * @param a Any Cell potentially representing a collection of items to be counted
	 * @return The count of elements in the collection, or null if not countable
	 */
	public static Long count(ACell a) {
		if (a == null) return 0L;
		if (a instanceof ACountable) return ((ACountable<?>) a).count();
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
	 * @return AString value
	 */
	public static AString str(ACell[] args) {
		// TODO: execution cost limits??
		StringBuilder sb = new StringBuilder();
		for (ACell o : args) {
			String s=RT.str(o);
			sb.append(s);
		}
		return Strings.create(sb.toString());
	}

	/**
	 * Converts a value to a CVM String representation. Required to work for all valid
	 * types.
	 * 
	 * @param a Value to convert to a String
	 * @return String representation of object
	 */
	public static String str(ACell a) {
		if (a == null) return "nil";
		if (a instanceof Blob) return ((Blob)a).toHexString();
		String s = a.toString();
		return s;
	}

	/**
	 * Gets the name from a CVM value. Supports Strings, Keywords and Symbols.
	 *
	 * @param a Value to cast to a name
	 * @return Name of the argument, or null if not Named
	 */
	public static AString name(ACell a) {
		if (a instanceof AString) return (AString) a;
		if (a instanceof Keyword) return Strings.create(((Keyword) a).getName());
		if (a instanceof Symbol) return Strings.create(((Symbol) a).getName());
		return null;
	}

	/**
	 * Prepends an element to a sequential data structure. The new element will
	 * always be in position 0
	 * 
	 * @param <T> Type of elements
	 * @param x   Element to prepend
	 * @param xs  Any sequential object, or null (will be treated as empty sequence)
	 * @return A new list with the cons'ed element at the start
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> AList<T> cons(T x, ASequence<?> xs) {
		if (xs == null) return Lists.of(x);
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
		if (xs == null) return Lists.of(x, y);
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
		return nxs.cons(y).cons(x).cons(z);
	}

	/**
	 * Coerces any object to a collection type, or returns null if not possible.
	 * Null is converted to an empty vector.
	 * 
	 * @param a value to coerce to collection type.
	 * @return Collection object, or null if coercion failed.
	 */
	@SuppressWarnings("unchecked")
	static <E extends ACell> ACollection<E> collection(ACell a) {
		if (a == null) return Vectors.empty();
		if (a instanceof ACollection) return (ACollection<E>) a;
		return null;
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
		if (a == null) return Vectors.empty();
		if (a instanceof ADataStructure) return (ADataStructure<E>) a;
		return null;
	}

	/**
	 * Coerces an argument to a function interface. Certain values e.g. Keywords can
	 * be used / applied in function position.
	 * 
	 * @param <T> Function return type
	 * @param a Value to cast to a function
	 * @return AFn instance, or null if the argument cannot be coerced to a
	 *         function.
	 * 
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> AFn<T> castFunction(ACell a) {
		if (a instanceof AFn) return (AFn<T>) a;
		if (a instanceof AMap) return MapFn.wrap((AMap<?, T>) a);
		if (a instanceof ASequence) return SeqFn.wrap((ASequence<?>) a);
		if (a instanceof ASet) return (AFn<T>) SetFn.wrap((ASet<?>) a);
		if (a instanceof Keyword) return KeywordFn.wrap((Keyword) a);
		return null;
	}
	
	/**
	 * Ensure the argument is a valid CVM function. Returns null otherwise.
	 * 
	 * @param <T> Function return type
	 * @param a Value to cast to a function
	 * @return IFn instance, or null if the argument cannot be coerced to a
	 *         function.
	 * 
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> AFn<T> ensureFunction(ACell a) {
		if (a instanceof AFn) return (AFn<T>) a;
		return null;
	}

	/**
	 * Casts the argument to a valid Address.
	 * 
	 * Handles:
	 * <ul>
	 * <li>Strings, which are interpreted as 16-character hex strings</li>
	 * <li>Addresses, which are returned unchanged </li>
	 * <li>Blobs, which are converted to addresses if and only if they are of the correct length (8 bytes)</li>
	 * <li>Numeric Longs, which are converted to the equivalent Address</li>
	 * </ul>
	 * 
	 * @param a Value to cast to an Address
	 * @return Address value or null if not castable to a valid address
	 */
	public static Address castAddress(ACell a) {
		if (a instanceof Address) return (Address) a;
		if (a instanceof ABlob) return Address.create((ABlob)a);
		if (a instanceof AString) return Address.fromHex(a.toString());
		CVMLong value=RT.ensureLong(a);
		if (value==null) return null;
		return Address.create(value.longValue());
	}
	
	/**
	 * Casts an arbitrary value to an Address
	 * @param a Value to cast. Strings or CVM values accepted
	 * @return Address instance, or null if not convertible
	 */
	public static Address castAddress(Object a) {
		if (a instanceof ACell) return castAddress((ACell)a);
		if (a instanceof String) return Address.parse((String)a);
		return null;
	}
	
	/**
	 * Ensures the argument is a valid Address.
	 * 
	 * @param a Value to cast
	 * @return Address value or null if not a valid address
	 */
	public static Address ensureAddress(ACell a) {
		if (a instanceof Address) return (Address) a;
		return null;
	}
	
	/**
	 * Implicit cast to an AccountKey. Accepts blobs of correct length
	 * @param a Value to cast
	 * @return AccountKey instance, or null if coercion fails
	 */
	public static AccountKey ensureAccountKey(ACell a) {
		if (a==null) return null;
		if (a instanceof AccountKey) return (AccountKey) a;
		if (a instanceof ABlob) {
			ABlob b = (ABlob) a;
			return AccountKey.create(b);
		}

		return null;
	}
	
	/**
	 * Coerce to an AccountKey. Accepts strings and blobs of correct length
	 * @param a Value to cast
	 * @return AccountKey instance, or null if coercion fails
	 */
	public static AccountKey castAccountKey(ACell a) {
		if (a==null) return null;
		if (a instanceof AString) return AccountKey.fromHexOrNull((AString)a);
		return ensureAccountKey(a);
	}

	/**
	 * Converts an object to a canonical blob representation. Handles blobs,
	 * addresses, hashes and hex strings
	 * 
	 * @param a Object to convert to a Blob
	 * @return Blob value, or null if not convertable to a blob
	 */
	public static ABlob castBlob(ACell a) {
		// handle address, hash, blob instances
		if (a instanceof ABlob) return Blobs.toCanonical((ABlob) a);
		if (a instanceof AString) return Blobs.fromHex(a.toString());
		return null;
	}

	/**
	 * Converts the argument to a non-null Map. Nulls are implicitly converted to the empty
	 * map.
	 * 
	 * @param <K> Type of map keys
	 * @param <V> Type of map values
	 * @param a Value to cast
	 * @return Map instance, or null if argument cannot be converted to a map
	 * 
	 */
	@SuppressWarnings("unchecked")
	public static <K extends ACell, V extends ACell> AMap<K, V> ensureMap(ACell a) {
		if (a == null) return Maps.empty();
		if (a instanceof AMap) return (AMap<K, V>) a;
		return null;
	}

	/**
	 * Gets an element from a data structure using the given key.
	 * 
	 * @param coll Collection to query
	 * @param key Key to look up in collection
	 * @return Value from collection with the specified key, or null if not found.
	 */
	public static ACell get(ADataStructure<?> coll, ACell key) {
		if (coll == null) return null;
		return coll.get(key);
	}

	/**
	 * Gets an element from a data structure using the given key. Returns the
	 * notFound parameter if the data structure does not have the specified key
	 * 
	 * @param coll Collection to query
	 * @param key Key to look up in collection
	 * @param notFound Value to return if the lookup failed
	 * @return Value from collection with the specified key, or notFound argument
	 *         if not found.
	 */
	public static ACell get(ADataStructure<?> coll, ACell key, ACell notFound) {
		if (coll == null) return notFound;
		return coll.get(key,notFound);
	}
	
	/**
	 * Converts any CVM value to a boolean value. An value is considered falsey if null
	 * or equal to CVMBool.FALSE, truthy otherwise
	 * 
	 * @param a Object to convert to boolean value
	 * @return true if object is truthy, false otherwise
	 */
	public static boolean bool(ACell a) {
		return !((a == null) || (a == CVMBool.FALSE));
	}
	
	/**
	 * Converts an object to a map entry. Handles MapEntries and length 2 vectors.
	 * 
	 * @param <K> Type of map key
	 * @param <V> Type of map value
	 * @param x Value to cast
	 * @return MapEntry instance, or null if conversion fails
	 */
	@SuppressWarnings("unchecked")
	public static <K extends ACell, V extends ACell> MapEntry<K, V> ensureMapEntry(ACell x) {
		MapEntry<K, V> me;
		if (x instanceof MapEntry) {
			me = (MapEntry<K, V>) x;
		} else if (x instanceof AVector) {
			AVector<?> v = (AVector<?>) x;
			if (v.count() != 2) return null;
			me = MapEntry.createRef(v.getRef(0), v.getRef(1));
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
		if (o instanceof Hash) return ((Hash) o);
		if (o instanceof ABlob) {
			ABlob blob=(ABlob)o;
			if (blob.count()!=Hash.LENGTH) return null;
			return Hash.wrap(blob.getBytes());
		}
			
		return null;
	}

	/**
	 * Coerces an named argument to a keyword.
	 * 
	 * @param a Value to cast
	 * @return Keyword if correctly constructed, or null if a failure occurs
	 */
	public static Keyword castKeyword(ACell a) {
		if (a instanceof Keyword) return (Keyword) a;
		AString name = name(a);
		if (name == null) return null;
		Keyword k = Keyword.create(name);
		return k;
	}
	
	/**
	 * Coerces an named argument to a Symbol.
	 * 
	 * @param a Value to cast
	 * @return Symbol if correctly constructed, or null if a failure occurs
	 */
	public static Symbol ensureSymbol(ACell a) {
		if (a instanceof Symbol) return (Symbol) a;
		return null;
	}



	/**
	 * Casts to an ADataStructure instance
	 * 
	 * @param <E> Type of data structure element
	 * @param a Value to cast
	 * @return ADataStructure instance, or null if not a data structure
	 */
	@SuppressWarnings("unchecked")
	public static <E extends ACell> ADataStructure<E> ensureDataStructure(ACell a) {
		if (a instanceof ADataStructure) return (ADataStructure<E>) a;
		return null;
	}
	
	/**
	 * Casts to an ACountable instance
	 * 
	 * @param <E> Type of countable element
	 * @param a Value to cast
	 * @return ADataStructure instance, or null if not a data structure
	 */
	@SuppressWarnings("unchecked")
	public static <E extends ACell> ACountable<E> ensureCountable(ACell a) {
		if (a instanceof ACountable) return (ACountable<E>) a;
		return null;
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
		if (a == null) return b;
		if (b == null) return a;
		return a.concat((ASequence) b);
	}

	/**
	 * Validates an object. Might be a Cell or Ref
	 * 
	 * @param o Object to validate
	 * @throws InvalidDataException For any validation failure
	 */
	public static void validate(Object o) throws InvalidDataException {
		if (o==null) return;
		if (o instanceof ACell) {
			((ACell) o).validate();
		} else if (o instanceof Ref) {
			((Ref<?>) o).validate();
		} else {
			throw new InvalidDataException("Data of class" + Utils.getClass(o)
						+ " neither IValidated, canonical nor embedded: ", o);
		}
		
	}

	/**
	 * Validate a Cell.
	 * 
	 * @param o Object to validate
	 * @throws InvalidDataException For any validation failure
	 */
	public static void validateCell(ACell o) throws InvalidDataException {
		if (o==null) return;
		if (o instanceof ACell) {
			((ACell) o).validateCell();
		}
	}

	/**
	 * Associates a key with a given value in an associative data structure
	 * 
	 * @param coll Any associative data structure
	 * @param key Key to update or add
	 * @param value Value to associate with key
	 * @return Updated data structure, or null if cast fails
	 */
	@SuppressWarnings("unchecked")
	public static <R extends ACell> ADataStructure<R> assoc(ADataStructure<R> coll, ACell key, ACell value) {
		if (coll == null) return (ADataStructure<R>) Maps.create(key, value);
		return coll.assoc(key, value);
	}

	/**
	 * Returns the vector of keys of a map, or null if the object is not a map
	 * 
	 * @param a Value to extract keys from (i.e. a Map)
	 * @return Vector of keys in the map
	 */
	@SuppressWarnings("unchecked")
	public static <R extends ACell> AVector<R> keys(ACell a) {
		if (!(a instanceof AMap)) return null;
		AMap<R, ACell> m = (AMap<R, ACell>) a;
		return m.reduceEntries(new BiFunction<>() {
			@Override
			public AVector<R> apply(AVector<R> t, MapEntry<R, ACell> u) {
				return t.conj(u.getKey());
			}
		}, Vectors.empty());
	}

	/**
	 * Returns the vector of values of a map, or null if the object is not a map
	 * 
	 * @param a Value to extract values from (i.e. a Map)
	 * @return Vector of values from a map, or null if the object is not a map
	 */
	@SuppressWarnings("unchecked")
	public static <R extends ACell> AVector<R> values(ACell a) {
		if (!(a instanceof AMap)) return null;
		AMap<ACell, R> m = (AMap<ACell, R>) a;
		return m.reduceValues(new BiFunction<AVector<R>, R, AVector<R>>() {
			@Override
			public AVector<R> apply(AVector<R> t, R u) {
				return t.conj(u);
			}
		}, Vectors.empty());
	}
	
	/**
	 * Ensures the argument is an IAssociative instance. A null argument is considered an empty map.
	 * 
	 * @param o Value to cast
	 * @return IAssociative instance, or null if conversion is not possible
	 */
	public static ADataStructure<?> ensureAssociative(ACell o) {
		if (o==null) return Maps.empty();
		if (o instanceof ADataStructure) return (ADataStructure<?>) o;
		return null;
	}

	/**
	 * Ensures the value is a set. null is converted to the empty set. 
	 * 
	 * Returns null if the argument is not a set.
	 * 
	 * @param <T> Type of set element
	 * @param a Value to cast
	 * @return A set instance, or null if the argument cannot be converted to a set
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> ASet<T> ensureSet(ACell a) {
		if (a==null) return Sets.empty();
		if (!(a instanceof ASet)) return null;
		return (ASet<T>) a;
	}

	/**
	 * Casts the argument to a hashmap. null is converted to the empty HashMap. 
	 * @param <K> Type of keys
	 * @param <V> Type of values
	 * @param a Any object
	 * @return AHashMap instance, or null if not a hash map
	 */
	@SuppressWarnings("unchecked")
	public static <K extends ACell,V extends ACell> AHashMap<K, V> ensureHashMap(ACell a) {
		if (a==null) return Maps.empty();
		if (a instanceof AHashMap) return (AHashMap<K, V>) a;
		return null;
	}

	/**
	 * Implicitly casts the argument to a Blob
	 * @param object Value to cast to Blob
	 * @return Blob instance, or null if cast fails
	 */
	public static ABlob ensureBlob(ACell object) {
		if (object instanceof ABlob) return ((ABlob)object);
		return null;
	}
	
	/**
	 * Implicitly casts the argument to a CVM String
	 * 
	 * @param a Value to cast to a String 
	 * @return AString instance, or null if cast fails
	 */
	public static AString ensureString(ACell a) {
		if (a instanceof AString) return ((AString)a);
		return null;
	}

	public static boolean isValidAmount(long amount) {
		return ((amount>=0)&&(amount<Constants.MAX_SUPPLY));
	}
	

	/**
	 * Converts a Java value to a CVM type
	 * 
	 * @param o Any Java Object
	 * @return Valid CVM type
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> T cvm(Object o) {
		if (o==null) return null;
		if (o instanceof ACell) return ((T)o);
		if (o instanceof String) return (T) Strings.create((String)o);
		if (o instanceof Double) return (T)CVMDouble.create(((Double)o));
		if (o instanceof Number) return (T)CVMLong.create(((Number)o).longValue());
		if (o instanceof Character) return (T)CVMChar.create((Character)o);
		if (o instanceof Boolean) return (T)CVMBool.create((Boolean)o);
		throw new IllegalArgumentException("Can't convert to CVM type with class: "+Utils.getClassName(o));
	}

	/**
	 * Converts a CVM value to equivalent JVM value
	 * @param o Value to convert to JVM type
	 * @return Java value, or unchanged input
	 */
	@SuppressWarnings("unchecked")
	public static <T> T jvm(ACell o) {
		if (o instanceof AString) return (T) o.toString();
		if (o instanceof CVMLong) return (T)(Long)((CVMLong)o).longValue();
		if (o instanceof CVMDouble) return (T)(Double)((CVMDouble)o).doubleValue();
		if (o instanceof CVMByte) return (T)(Byte)(byte)((CVMByte)o).longValue();
		if (o instanceof CVMBool) return (T)(Boolean)((CVMBool)o).booleanValue();
		if (o instanceof CVMChar) return (T)(Character)((CVMChar)o).charValue();
		return (T)o;
	}

	/**
	 * Compute mode. 
	 * @param a First numeric argument (numerator)
	 * @param b First numeric argument (divisor)
	 * @return Numeric value or null if cast fails
	 */
	public static CVMLong mod(ACell a , ACell b) {
		CVMLong la=RT.castLong(a);
		if (la==null) return null;
		
		CVMLong lb=RT.castLong(b);
		if (lb==null) return null;

		long num = la.longValue();
		long denom = lb.longValue();
		long result = num % denom;
		if (result<0) result+=denom;
		
		return CVMLong.create(result);
	}

	/**
	 * Get the runtime Type of any CVM value
	 * @param a Any CVM value
	 * @return Type of CVM value
	 */
	public static AType getType(ACell a) {
		if (a==null) return Types.NIL;
		return a.getType();
	}

	public static boolean isNaN(ACell val) {
		return CVMDouble.NaN.equals(val);
	}





}
