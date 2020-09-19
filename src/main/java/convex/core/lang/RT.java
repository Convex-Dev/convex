package convex.core.lang;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.function.BiFunction;

import convex.core.crypto.Hash;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.ACollection;
import convex.core.data.ADataStructure;
import convex.core.data.AHashMap;
import convex.core.data.AList;
import convex.core.data.AMap;
import convex.core.data.ASequence;
import convex.core.data.ASet;
import convex.core.data.AVector;
import convex.core.data.Address;
import convex.core.data.Amount;
import convex.core.data.Blobs;
import convex.core.data.Format;
import convex.core.data.IGet;
import convex.core.data.IValidated;
import convex.core.data.Keyword;
import convex.core.data.Lists;
import convex.core.data.MapEntry;
import convex.core.data.Maps;
import convex.core.data.Ref;
import convex.core.data.Sets;
import convex.core.data.Symbol;
import convex.core.data.Vectors;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.impl.KeyFn;
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
	 * @param <T>
	 * @param values
	 * @return True i
	 */
	public static <T> Boolean allEqual(T[] values) {
		for (int i = 0; i < values.length - 1; i++) {
			if (!Utils.equals(values[i], values[i + 1])) return false;
		}
		return true;
	}

	// Numerical comparison functions

	/**
	 * Check if the values passed are a short (length 0 or 1) array of numbers which
	 * is a special case for comparison operations.
	 */
	private static Boolean checkShortCompare(Object[] values) {
		int len = values.length;
		if (len == 0) return true;
		if (len == 1) {
			if (null == RT.number(values[0])) return null; // cast failure
			return true;
		}
		return false;
	}

	public static Boolean eq(Object[] values) {
		Boolean check = checkShortCompare(values);
		if (check == null) return null;
		if (check) return true;
		for (int i = 0; i < values.length - 1; i++) {
			Long comp = RT.compare(values[i], values[i + 1]);
			if (comp == null) return null; // cast error
			if (comp != 0) return false;
		}
		return true;
	}

	public static Boolean ge(Object[] values) {
		Boolean check = checkShortCompare(values);
		if (check == null) return null;
		if (check) return true;
		for (int i = 0; i < values.length - 1; i++) {
			Long comp = RT.compare(values[i], values[i + 1]);
			if (comp == null) return null; // cast error
			if (comp < 0) return false;
		}
		return true;
	}

	public static Boolean gt(Object[] values) {
		Boolean check = checkShortCompare(values);
		if (check == null) return null;
		if (check) return true;
		for (int i = 0; i < values.length - 1; i++) {
			Long comp = RT.compare(values[i], values[i + 1]);
			if (comp == null) return null; // cast error
			if (comp <= 0) return false;
		}
		return true;
	}

	public static Boolean le(Object[] values) {
		Boolean check = checkShortCompare(values);
		if (check == null) return null;
		if (check) return true;
		for (int i = 0; i < values.length - 1; i++) {
			Long comp = RT.compare(values[i], values[i + 1]);
			if (comp == null) return null; // cast error
			if (comp > 0) return false;
		}
		return true;
	}

	public static Boolean lt(Object[] values) {
		Boolean check = checkShortCompare(values);
		if (check == null) return null;
		if (check) return true;
		for (int i = 0; i < values.length - 1; i++) {
			Long comp = RT.compare(values[i], values[i + 1]);
			if (comp == null) return null; // cast error
			if (comp >= 0) return false;
		}
		return true;
	}

	/**
	 * Get the target common numeric type for a given set of arguments. - Integers
	 * upcast to Long - Anything else upcasts to Double
	 * 
	 * @param args
	 * 
	 * @return The target numeric type, or null if there is a non-numeric argument
	 */
	public static Class<?> commonNumericType(Object[] args) {
		for (int i = 0; i < args.length; i++) {
			Object a = args[i];
			Class<?> klass = numericType(a);
			if (klass == null) return null;
			if (klass == Double.class) return Double.class;
		}
		return Long.class;
	}

	/**
	 * Finds the first non-numeric value in an array. USed for error reporting.
	 * 
	 * @param args
	 * @return First non-numeric value, or null if not found.
	 */
	public static Object findNonNumeric(Object[] args) {
		for (int i = 0; i < args.length; i++) {
			Object a = args[i];
			Class<?> klass = numericType(a);
			if (klass == null) return a;
		}
		return null;
	}

	/**
	 * Gets the numeric class of an object
	 * 
	 * @param a
	 * @return Long.class or Double.class if cast possible, or null if not numeric.
	 */
	public static Class<?> numericType(Object a) {
		if (!(a instanceof Number)) {
			if (a instanceof Character) return Long.class; // Only non-number we allow, can upcast to long
			return null;
		}
		if (a instanceof Long || a instanceof Integer || a instanceof Short || a instanceof Byte) {
			return Long.class;
		}
		return Double.class;
	}

	public static Number plus(Object[] args) {
		Class<?> type = commonNumericType(args);
		if (type == null) return null;
		if (type == Double.class) return plusDouble(args);
		long result = 0;
		for (int i = 0; i < args.length; i++) {
			result += RT.longValue(args[i]);
		}
		return result;
	}

	public static Double plusDouble(Object[] args) {
		double result = 0;
		for (int i = 0; i < args.length; i++) {
			result += RT.doubleValue(args[i]);
		}
		return result;
	}

	public static Number minus(Object[] args) {
		Class<?> type = commonNumericType(args);
		if (type == null) return null;
		if (type == Double.class) return minusDouble(args);
		int n = args.length;
		long result = longValue(args[0]);
		if (n == 1) return -result;
		for (int i = 1; i < args.length; i++) {
			result -= RT.longValue(args[i]);
		}
		return result;
	}

	public static Double minusDouble(Object[] args) {
		int n = args.length;
		double result = doubleValue(args[0]);
		if (n == 1) return -result;
		for (int i = 1; i < args.length; i++) {
			result -= RT.doubleValue(args[i]);
		}
		return result;
	}

	public static Number times(Object[] args) {
		Class<?> type = commonNumericType(args);
		if (type == null) return null;
		if (type == Double.class) return timesDouble(args);
		long result = 1;
		for (int i = 0; i < args.length; i++) {
			result *= RT.longValue(args[i]);
		}
		return result;
	}

	public static Number timesDouble(Object[] args) {
		double result = 1;
		for (int i = 0; i < args.length; i++) {
			result *= RT.doubleValue(args[i]);
		}
		return result;
	}

	public static Double divide(Object[] args) {
		int n = args.length;
		Double result = toDouble(args[0]);
		if (result == null) return null;

		if (n == 1) return 1.0 / result;
		for (int i = 1; i < args.length; i++) {
			Double v = toDouble(args[i]);
			if (v == null) return null;
			result = result / v;
		}
		return result;
	}

	public static Double pow(Object[] args) {
		double a = doubleValue(args[0]);
		double b = doubleValue(args[1]);
		return StrictMath.pow(a, b);
	}

	public static Double exp(Object arg) {
		double a = doubleValue(arg);
		return StrictMath.exp(a);
	}

	/**
	 * Gets the exact positive square root of a number after casting to a double.
	 * Returns NaN for negative numbers.
	 * 
	 * @param a
	 * @return The square root of the number, or null if cast fails
	 */
	public static Double sqrt(Object a) {
		Double d = RT.toDouble(a);
		if (d == null) return null;
		return StrictMath.sqrt(d);
	}
	
	/**
	 * Gets the absolute value of a numeric value. Supports double and long.
	 * 
	 * @param a Numeric value
	 * @return
	 */
	public static Number abs(Object a) {
		Number x=RT.number(a);
		if (x==null) return null;
		if (x instanceof Long) return Math.abs((Long)x);
		return Math.abs((Double)x);
	}
	
	/**
	 * Gets the signum of a numeric value
	 * 
	 * @param a Numeric value
	 * @return Long value of -1, 0 or 1, or null if the argument is not numeric
	 */
	public static Long signum(Object a) {
		Number x=RT.number(a);
		if (x==null) return null;
		if (x instanceof Long) return (long) Long.signum((Long)x);
		double xd=(Double)x;
		if (Double.isNaN(xd)) return null;
		return (long)Math.signum(xd);
	}

	/**
	 * Compares two objects representing numbers numerically.
	 * 
	 * @param a First numeric value
	 * @param b Second numeric value
	 * @return Less than 0 if a is smaller, greater than 0 if a is larger, 0 if a
	 *         equals b
	 */
	public static Long compare(Object a, Object b) {
		Class<?> ca = numericType(a);
		if (ca == null) return null;
		Class<?> cb = numericType(b);
		if (cb == null) return null;

		if ((ca == Long.class) && (cb == Long.class)) return RT.compare(longValue(a), longValue(b));

		return RT.compare(doubleValue(a), doubleValue(b));
	}

	/**
	 * Compares two double values numerically, according to Java primitive
	 * comparisons. Note: slight difference from Double.compare(...) due to signed
	 * zeros and NAN
	 * 
	 * @param a
	 * @param b
	 * @return negative if first argument is smaller, positive if second argument is
	 *         smaller, 0 otherwise
	 */
	public static long compare(double a, double b) {
		if (a < b) return -1;
		if (a > b) return 1;
		return 0;
	}

	/**
	 * Compares two long values numerically, according to Java primitive
	 * comparisons.
	 * 
	 * @param a
	 * @param b
	 * @return -1 if a<b, 1 if a>b, 0 is they are equal
	 */
	public static long compare(long a, long b) {
		if (a < b) return -1;
		if (a > b) return 1;
		return 0;
	}

	/**
	 * Coerces a value to a canonical numeric value. Result will be one of: 
	 * <ul> 
	 * <li>Long for Byte, Integer, Short, Long, Amount, Character, Blob</li>
	 * <li>Double for Double, Float </li>
	 * </ul>
	 * 
	 * @param a
	 * @return The number value, or null if cannot be converted
	 */
	public static Number number(Object a) {
		if (a == null) return null;

		Class<?> c = a.getClass();
		// canonical numeric types
		if (c == Long.class) return (Long) a;
		if (c == Double.class) return (Double) a;

		// other numeric primitives need widening
		if (c == Byte.class) return ((Byte) a).longValue();
		if (c == Integer.class) return ((Integer) a).longValue();
		if (c == Short.class) return ((Short) a).longValue();
		if (c == Amount.class) return ((Amount) a).getValue();
		if (c == Float.class) return (Double) a;

		if (c == Character.class) return (long) ((Character) a);
		
		if (a instanceof ABlob) {
			return (Long)((ABlob)a).toLong();
		}

		return null;
	}

	public static Long inc(Object object) {
		Long n = toLong(object);
		if (n == null) return null;
		return (Long) (n + 1L);
	}

	public static Long dec(Object object) {
		Long n = toLong(object);
		if (n == null) return null;
		return (Long) (n - 1L);
	}

	public static Double toDouble(Object a) {
		if (a instanceof Double) return (Double) a;
		Number n = number(a);
		if (n == null) return null;
		return n.doubleValue();
	}

	/**
	 * Converts a numerical value to a Long. Doubles and floats will be converted if possible.
	 * @param a
	 * @return Long value, or null if not convertible
	 */
	public static Long toLong(Object a) {
		if (a instanceof Long) return (Long) a;
		Number n = number(a);
		if (n == null) return null;
		return n.longValue();
	}

	public static Byte toByte(Object a) {
		if (a instanceof Byte) return (Byte) a;
		Number n = number(a);
		if (n == null) return null;
		return (byte) n.longValue();
	}

	public static Short toShort(Object a) {
		if (a instanceof Short) return (Short) a;
		Number n = number(a);
		if (n == null) return null;
		return (short) n.longValue();
	}

	public static Integer toInteger(Object a) {
		if (a instanceof Integer) return (Integer) a;
		Number n = number(a);
		if (n == null) return null;
		return (int) n.longValue();
	}

	public static Character toCharacter(Object a) {
		if (a instanceof Character) return (Character) a;
		Number n = number(a);
		if (n == null) return null;
		return (char) n.longValue();
	}

	public static Amount toAmount(Object a) {
		if (a instanceof Amount) return ((Amount) a);
		Long value = toLong(a);
		if (value == null) return null;
		return Amount.create(value);
	}

	private static long longValue(Object a) {
		if (a instanceof Long) return (Long) a;
		if (a instanceof Number) return ((Number) a).longValue();
		if (a instanceof Character) return (long) ((char) a);
		throw new IllegalArgumentException("Can't convert to double: " + Utils.getClassName(a));
	}

	private static double doubleValue(Object a) {
		if (a instanceof Double) return (Double) a;
		if (a instanceof Number) return ((Number) a).doubleValue();
		throw new IllegalArgumentException("Can't convert to double: " + Utils.getClassName(a));
	}

	/**
	 * Converts any data structure to a vector
	 * 
	 * @return AVector instance
	 */
	@SuppressWarnings("unchecked")
	public static <T> AVector<T> vec(Object o) {
		if (o == null) return Vectors.empty();
		if (o instanceof ACollection) return vec((ACollection<T>) o);
		return vec(sequence(o));
	}

	/**
	 * Converts any collection to a set
	 */
	@SuppressWarnings("unchecked")
	public static <T> ASet<T> set(Object o) {
		if (o == null) return Sets.empty();
		if (o instanceof ASet) return (ASet<T>) o;
		if (o instanceof Collection) return Sets.create((Collection<T>) o);
		return null;
	}

	/**
	 * Converts any collection to a vector.
	 * 
	 * Null values are converted to empty vector (considered as empty sequence)
	 */
	public static <T> AVector<T> vec(ACollection<T> coll) {
		if (coll == null) return Vectors.empty();
		return coll.toVector();
	}

	/**
	 * Converts any collection into a sequence data structure.
	 * 
	 * Nulls are converted to an empty vector.
	 * 
	 * Returns null if conversion is not possible.
	 * 
	 * @param <T>
	 * @param o
	 * @return An ASequence instance, or null if the argument cannot be converted to
	 *         a sequence
	 */
	@SuppressWarnings("unchecked")
	public static <T> ASequence<T> sequence(Object o) {
		if (o == null) return Vectors.empty();
		if (o instanceof ASequence) return (ASequence<T>) o;
		if (o instanceof ACollection) return ((ACollection<T>) o).toVector();
		if (o instanceof AMap) {
			// TODO: probably needs fixing! SECURITY
			return sequence(((AMap<?, ?>) o).entryVector());
		}

		if (o.getClass().isArray()) {
			T[] arr = (T[]) Utils.toObjectArray(o);
			return Vectors.create(arr);
		}

		if (o instanceof java.util.List) return Vectors.create((java.util.List<T>) o);

		return null;
	}

	/**
	 * Merges a sequence of items into a data structure.
	 * 
	 * @return The updated data structure, or null if any added elements were
	 *         invalid.
	 */
	public static <T> ADataStructure<T> into(ADataStructure<T> ds, ASequence<T> coll) {
		ADataStructure<T> result = ds;
		for (T o : coll) {
			result = result.conj(o);
			if (result == null) return null;
		}
		return result;
	}

	/**
	 * Gets the nth element from a sequential collection
	 * 
	 * @param <T> Type of element in collection
	 * @param o
	 * @return Element from collection at the specified position
	 */
	@SuppressWarnings("unchecked")
	public static <T> T nth(Object o, long i) {
		// special case, we treat nil as empty sequence
		if (o == null) throw new IndexOutOfBoundsException("Can't get nth element from null");

		try {
			if (o instanceof String) return (T) (Character) ((String) o).charAt(Utils.checkedInt(i));
			if (o instanceof ASequence) return ((ASequence<T>) o).get(i);
			if (o instanceof ABlob) return (T) (Byte) ((ABlob) o).get(i);

			// shouldn't get called in on-chain code, but needed for destructuring / runtime
			// optimisations
			if (o.getClass().isArray()) {
				return (T) Array.get(o, Utils.checkedInt(i));
			}

			ASequence<?> seq = sequence(o);
			if (seq == null)
				throw new ClassCastException("Can't get nth element from object of class: " + Utils.getClassName(o));

			return (T) seq.get(i);
		} catch (IllegalArgumentException e) {
			// can come from checkedInt calls
			throw new IndexOutOfBoundsException(e.getMessage());
		}
	}

	/**
	 * Gets the remainder of a sequence after the first element, or null if there
	 * are no more elements.
	 * 
	 * @param <T>
	 * @param o
	 * @return The remaining sequence, or null if no more elements
	 */
	@SuppressWarnings("unchecked")
	public static <T> ASequence<T> next(Object o) {
		if (o == null) return null;
		if (o instanceof ASequence) return ((ASequence<T>) o).next();
		throw new IllegalArgumentException("Not a sequence: " + Utils.getClassName(o));
	}

	/**
	 * Gets the count of elements in a collection
	 * 
	 * @param o An object representing a collection of items to be counted
	 * @return The count of elements in the collection, or null if not countable
	 */
	public static Long count(Object o) {
		if (o == null) return 0L;
		if (o instanceof ADataStructure) return ((ADataStructure<?>) o).count();
		if (o instanceof String) {
			return (long) ((String) o).length();
		}
		if (o instanceof ABlob) {
			return ((ABlob) o).length();
		}
		if (o.getClass().isArray()) {
			return (long) Array.getLength(o);
		}
		return null;
	}

	/**
	 * Coerces any object to a boolean value. An object is considered falsey if null
	 * or equals to Boolean.FALSE, truthy otherwise
	 * 
	 * @param a Object to convert to boolean value
	 * @return true if object is truthy, false otherwise
	 */
	public static boolean bool(Object a) {
		return !((a == null) || (a == Boolean.FALSE));
	}

	/**
	 * Converts arguments to a string representation. Handles:
	 * <ul>
	 * <li>Strings (unchanged)</li>
	 * <li>Blobs (converted to hex)</li>
	 * <li>Numbers (converted to canonical numeric representation)</li>
	 * <li>Other Objects (represented as edn)
	 * </ul>
	 * 
	 * @param args
	 */
	public static String str(Object[] args) {
		StringBuilder sb = new StringBuilder();
		for (Object o : args) {
			sb.append(RT.str(o));
		}
		return sb.toString();
	}

	/**
	 * Converts an value to a String representation. Required to work for all valid
	 * types.
	 * 
	 * @param a
	 * @return String representation of object
	 */
	public static String str(Object a) {
		if (a == null) return "nil";
		if (a instanceof String) return (String) a;
		if (a instanceof Number) return a.toString();
		if (a instanceof ABlob) return ((ABlob) a).toHexString();
		if (a instanceof ACell) return ((ACell) a).ednString();
		if (a instanceof Boolean || a instanceof Character) return a.toString();
		return null;
	}

	/**
	 * Gets the name from an Object. Supports Strings, Keywords and Symbols.
	 *
	 * @param a
	 * @return Name of the argument, or null if not Named
	 */
	public static String name(Object a) {
		if (a instanceof String) return (String) a;
		if (a instanceof Keyword) return ((Keyword) a).getName();
		if (a instanceof Symbol) return ((Symbol) a).getName();
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
	public static <T> AList<T> cons(T x, ASequence<?> xs) {
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
	public static <T> AList<T> cons(T x, T y, Object xs) {
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
	public static <T> AList<T> cons(T x, T y, T z, Object xs) {
		ASequence<T> nxs = RT.sequence(xs);
		return nxs.cons(y).cons(x).cons(z);
	}

	/**
	 * Coerces any object to a collection type, or returns null if not possible.
	 * Null is converted to an empty vector.
	 * 
	 * @param a Object to coerce to collection type.
	 * @return Collection object, or null if coercion failed.
	 */
	@SuppressWarnings("unchecked")
	static <E> ADataStructure<E> collection(Object a) {
		if (a == null) return Vectors.empty();
		if (a instanceof ADataStructure) return (ADataStructure<E>) a;
		return null;
	}

	/**
	 * Coerces an argument to a function interface
	 * 
	 * @param <T>
	 * @param a
	 * @return IFn instance, or null if the argument cannot be coerced to a
	 *         function.
	 * 
	 */
	@SuppressWarnings("unchecked")
	public static <T> IFn<T> function(Object a) {
		if (a instanceof AFn) return (IFn<T>) a;
		if (a instanceof AMap) return MapFn.wrap((AMap<?, T>) a);
		if (a instanceof ASequence) return SeqFn.wrap((ASequence<?>) a);
		if (a instanceof ASet) return (IFn<T>) SetFn.wrap((ASet<?>) a);
		if (a instanceof Keyword) return (IFn<T>) KeyFn.wrap((Keyword) a);

		return null;
	}

	/**
	 * Casts the argument to a valid Address.
	 * 
	 * Handles - Strings, which are interpreted as 64-character hex strings -
	 * Addresses, which are returned unchanged - Blobs, which are converted to
	 * addresses if and only if they are of the correct length (32 bytes)
	 * 
	 * @param a
	 * @return Address value or null if not a valid address
	 */
	public static Address address(Object a) {
		if (a instanceof Address) return (Address) a;
		if (a instanceof String) return address((String) a);
		if (a instanceof ABlob) {
			ABlob b = (ABlob) a;
			if (b.length() != Address.LENGTH) return null;
			return Address.wrap(b.getBytes());
		}

		return null;
	}

	/**
	 * Casts a String argument to a valid Address.
	 * 
	 * @return Address value, or null of String does not represent an address
	 *         (argument error)
	 */
	private static Address address(String a) {
		return Address.fromHexOrNull((String) a);
	}

	/**
	 * Converts an object to a canonical blob representation. Handles blobs,
	 * addresses, hashes and hex strings
	 * 
	 * @param a Object to convert to a Blob
	 * @return Blob value, or null if not convertable to a blob
	 */
	public static ABlob blob(Object a) {
		// handle address, hash, blob instances
		if (a instanceof ABlob) return Blobs.canonical((ABlob) a);
		if (a instanceof String) return Blobs.fromHex((String) a);
		return null;
	}

	/**
	 * Converts the argument to a non-null Map. Nulls are converted to the empty
	 * map.
	 * 
	 * @param <K>
	 * @param <V>
	 * @param a
	 * @return Map instance, or null if argument cannot be converted to a map
	 * 
	 */
	@SuppressWarnings("unchecked")
	public static <K, V> AMap<K, V> toMap(Object a) {
		if (a == null) return Maps.empty();
		if (a instanceof AMap) return (AMap<K, V>) a;
		return null;
	}
	
	@SuppressWarnings("unchecked")
	public static <K, V> AHashMap<K, V> toHashMap(Object a) {
		if (a == null) return Maps.empty();
		AMap<K, V> m=RT.toMap(a);
		if (m instanceof AHashMap) return (AHashMap<K, V>) a;
		return null;
	}

	/**
	 * Casts the argument to a non-null Set. Nulls are converted to the empty set.
	 * 
	 * @param <V>
	 * @param a
	 * @return A set instance, or null if the argument cannot be converted to a set
	 */
	@SuppressWarnings("unchecked")
	public static <V> ASet<V> toSet(Object a) {
		if (a == null) return Sets.empty();
		if (a instanceof ASet) return (ASet<V>) a;
		return null;
	}

	/**
	 * Gets an element from a data structure using the given key.
	 * 
	 * @param coll
	 * @param key
	 * @return Object from collection with the specified key, or null if not found.
	 */
	public static Object get(IGet<?> coll, Object key) {
		if (coll == null) return null;
		return coll.get(key);
	}

	/**
	 * Gets an element from a data structure using the given key. Returns the
	 * notFound parameter if the data structure does not have the specified key
	 * 
	 * @param coll
	 * @param key
	 * @return Object from collection with the specified key, or notFound argument
	 *         if not found.
	 */
	@SuppressWarnings("unchecked")
	public static Object get(Object coll, Object key, Object notFound) {
		if (coll == null) return notFound;
		if (coll instanceof AMap) return ((AMap<Object, Object>) coll).get(key, notFound);
		if (coll instanceof ASet) return ((ASet<Object>) coll).contains(key) ? key : notFound;
		if (coll instanceof ASequence) {
			// we consider Long keys only to be included
			if (key instanceof Long) {
				ASequence<Object> seq = (ASequence<Object>) coll;
				long ix = (Long) key;
				if ((ix >= 0) && (ix < seq.count())) return seq.get(ix);
			}
			return notFound;
		}
		throw new Error("Can't get from an object of type: " + Utils.getClass(coll));
	}

	/**
	 * Convert any value to a Boolean object.
	 * 
	 * @param o
	 * @return A boolean value representing false or true
	 */
	public static Boolean toBoolean(Object o) {
		if (RT.bool(o)) return Boolean.TRUE;
		return Boolean.FALSE;
	}

	/**
	 * Converts an object to a map entry. Handles MapEntries and length 2 vectors.
	 * 
	 * @param <K>
	 * @param <V>
	 * @param x
	 * @return MapEntry instance, or null if conversion fails
	 */
	@SuppressWarnings("unchecked")
	public static <K, V> MapEntry<K, V> toMapEntry(Object x) {
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
	 * Coerces to Hash type
	 * 
	 * @param o
	 * @return Hash instance, or null if conversion not possible
	 */
	public static Hash toHash(Object o) {
		if (o instanceof Hash) return ((Hash) o);
		if (o instanceof ABlob) {
			ABlob blob=(ABlob)o;
			if (blob.length()!=Hash.LENGTH) return null;
			return Hash.wrap(blob.getBytes());
		}
			
		return null;
	}

	/**
	 * Coerces an named argument to a keyword.
	 * 
	 * @param a
	 * @return Keyword if correctly constructed, or null if a failure occurs
	 */
	public static Keyword toKeyword(Object a) {
		if (a instanceof Keyword) return (Keyword) a;
		String name = name(a);
		if (name == null) return null;
		Keyword k = Keyword.create(name);
		return k;
	}

	/**
	 * Coerces an named argument to a Symbol.
	 * 
	 * @param a
	 * @return Symbol if correctly constructed, or null if a failure occurs
	 */
	public static Symbol toSymbol(Object a) {
		if (a instanceof Symbol) return (Symbol) a;
		String name = name(a);
		if (name == null) return null;
		return Symbol.create(name);
	}

	/**
	 * Gets the canonical String name for a String, Symbol or Keyword
	 * 
	 * @param a
	 * @return Name from the Object, or null if not a named object
	 */
	public static String getName(Object a) {
		if (a instanceof Keyword) return ((Keyword) a).getName();
		if (a instanceof Symbol) return ((Symbol) a).getName();
		if (a instanceof String) return ((String) a);
		return null;
	}

	/**
	 * Converts to an ADataStructure interface
	 * 
	 * @param <E>
	 * @param a
	 * @return ADataStructure instance, or null if not a data structure
	 */
	@SuppressWarnings("unchecked")
	public static <E> ADataStructure<E> toDataStructure(Object a) {
		if (a instanceof ADataStructure) return (ADataStructure<E>) a;
		return null;
	}

	/**
	 * Tests is the object is one of the canonical values true or false
	 * 
	 * @param value Value to test
	 * @return True if the value is a canonical boolean value.
	 */
	public static boolean isBoolean(Object value) {
		return (value == Boolean.TRUE) || (value == Boolean.FALSE);
	}

	/**
	 * Concatenates two sequences. Ignores nulls.
	 * 
	 * @param a First sequence. Will be used to determine the type of the result if
	 *          not null.
	 * @param b Second sequence. Will be the result if the first parameter is null.
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public static ASequence<?> concat(ASequence<?> a, ASequence<?> b) {
		if (a == null) return b;
		if (b == null) return a;
		return a.concat((ASequence) b);
	}

	/**
	 * Validates an object.
	 * 
	 * @param o
	 * @throws InvalidDataException For any miscellaneous validation failure @ If
	 *                              the data has missing data
	 */
	public static void validate(Object o) throws InvalidDataException {
		if (o instanceof IValidated) {
			((IValidated) o).validate();
		} else if (o instanceof Ref) {
			((Ref<?>) o).validate();
		} else if (!Format.isEmbedded(o)) {
			// TODO: figure out what counts as valid
			if (o instanceof BigDecimal) return;
			if (o instanceof BigInteger) return;
			if (o instanceof String) return;
			throw new InvalidDataException("Data of class" + Utils.getClass(o)
					+ " neither IValidated, canonical nor embedded: " + Utils.ednString(o), o);
		}
	}

	public static void validateCell(Object o) throws InvalidDataException {
		if (o instanceof ACell) {
			((ACell) o).validateCell();
		}
	}

	@SuppressWarnings("unchecked")
	public static <K, V> ADataStructure<?> assoc(Object coll, K key, V value) {
		if (coll == null) return Maps.create(key, value);
		if (coll instanceof AMap) {
			return ((AMap<K, V>) coll).assoc(key, value);
		} else if (coll instanceof ASequence) {
			if (!(key instanceof Long)) return null;
			return ((ASequence<V>) coll).assoc((long) key, value);
		}
		return null;
	}

	/**
	 * Returns the vector of keys of a map, or null if the object is not a map
	 * 
	 * @param object
	 * @return Vector of keys in the map
	 */
	@SuppressWarnings("unchecked")
	public static AVector<Object> keys(Object a) {
		if (!(a instanceof AMap)) return null;
		AMap<Object, Object> m = (AMap<Object, Object>) a;
		return m.reduceEntries(new BiFunction<AVector<Object>, MapEntry<Object, Object>, AVector<Object>>() {
			@Override
			public AVector<Object> apply(AVector<Object> t, MapEntry<Object, Object> u) {
				return t.conj(u.getKey());
			}
		}, Vectors.empty());
	}

	/**
	 * Returns the vector of values of a map, or null if the object is not a map
	 * 
	 * @param object
	 * @return VEctor of values from a map, or null if the object is not a map
	 */
	@SuppressWarnings("unchecked")
	public static AVector<Object> values(Object a) {
		if (!(a instanceof AMap)) return null;
		AMap<Object, Object> m = (AMap<Object, Object>) a;
		return m.reduceValues(new BiFunction<AVector<Object>, Object, AVector<Object>>() {
			@Override
			public AVector<Object> apply(AVector<Object> t, Object u) {
				return t.conj(u);
			}
		}, Vectors.empty());
	}

	/**
	 * Converts the argument to an IGet instance. A null argument is considered an empty map.
	 * 
	 * @param <T>
	 * @param o
	 * @return IGet instance, or null if conversion is not possible
	 */
	@SuppressWarnings("unchecked")
	public static <T> IGet<T> toGettable(Object o) {
		if (o==null) return Maps.empty();
		if (o instanceof IGet) return (IGet<T>) o;
		return null;
	}




}
