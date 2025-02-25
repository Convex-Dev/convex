package convex.core.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import convex.core.cvm.Address;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.ASequence;
import convex.core.data.AString;
import convex.core.data.Keyword;
import convex.core.data.MapEntry;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMChar;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.data.util.BlobBuilder;
import convex.core.lang.RT;

public class JSONUtils {

	/**
	 * Converts a CVM value to equivalent JSON value as expressed in equivalent JVM
	 * types.
	 * 
	 * Note some special one-way conversions that are required because JSON is not
	 * sufficiently expressive for all CVM types: - Address becomes a Number (Long
	 * type) - Lists and Vectors both become an Array (Java List type) - Characters
	 * become a String - Blobs become a hex string representation '0x....'
	 * 
	 * @param o Value to convert to JSON value object
	 * @return Java Object which represents JSON value
	 */
	@SuppressWarnings("unchecked")
	public static <T> T json(ACell o) {
		if (o == null)
			return null;
		if (o instanceof CVMLong)
			return (T) (Long) ((CVMLong) o).longValue();
		if (o instanceof CVMDouble)
			return (T) (Double) ((CVMDouble) o).doubleValue();
		if (o instanceof CVMBool)
			return (T) (Boolean) ((CVMBool) o).booleanValue();
		if (o instanceof CVMChar)
			return (T) ((CVMChar) o).toString();
		if (o instanceof Address)
			return (T) (Long) ((Address) o).longValue();
		if (o instanceof AMap) {
			AMap<?, ?> m = (AMap<?, ?>) o;
			return (T) JSONUtils.jsonMap(m);
		}
		if (o instanceof ASequence) {
			ASequence<?> seq = (ASequence<?>) o;
			long n = seq.count();
			ArrayList<Object> list = new ArrayList<>();
			for (long i = 0; i < n; i++) {
				ACell cvmv = seq.get(i);
				Object v = json(cvmv);
				list.add(v);
			}
			return (T) list;
		}
	
		return (T) o.toString();
	}

	/**
	 * Gets a String from a value suitable for use as a JSON map key
	 * 
	 * @param k Value to convert to a JSON key
	 * @return String usable as JSON key
	 */
	public static String jsonKey(ACell k) {
		if (k instanceof AString)
			return k.toString();
		if (k instanceof Keyword)
			return ((Keyword) k).getName().toString();
		return RT.toString(k);
	}

	/**
	 * Converts a CVM Map to a JSON representation
	 * 
	 * @param m Map to convert to JSON representation
	 * @return Java value which represents JSON object
	 */
	public static HashMap<String, Object> jsonMap(AMap<?, ?> m) {
		int n = m.size();
		HashMap<String, Object> hm = new HashMap<String, Object>(n);
		for (long i = 0; i < n; i++) {
			MapEntry<?, ?> me = m.entryAt(i);
			ACell k = me.getKey();
			String sk = jsonKey(k);
			Object v = json(me.getValue());
			hm.put(sk, v);
		}
		return hm;
	}
	
	public static String toString(Object value) {
		return toCVMString(value).toString();
	}

	private static AString toCVMString(Object value) {
		BlobBuilder bb=new BlobBuilder();
		appendCVMString(bb,value);
		return Strings.create(bb.toBlob());
	}
		
	private static void appendCVMString(BlobBuilder bb,Object value) {
		if (value==null) bb.append(Strings.NULL);
		
		if (value instanceof List lv) {
			bb.append('[');
			int n=lv.size();
			for (int i=0; i<n; i++) {
				appendCVMString(bb,lv.get(i));
				bb.append(' ');
			}
			bb.append(']');
		}
		
	}

}
