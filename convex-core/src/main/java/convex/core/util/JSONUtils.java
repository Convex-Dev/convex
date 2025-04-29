package convex.core.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import convex.core.cvm.Address;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.ACollection;
import convex.core.data.AMap;
import convex.core.data.ASequence;
import convex.core.data.AString;
import convex.core.data.ASymbolic;
import convex.core.data.Blob;
import convex.core.data.Keyword;
import convex.core.data.MapEntry;
import convex.core.data.StringShort;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMChar;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.data.util.BlobBuilder;
import convex.core.json.JSONReader;
import convex.core.lang.RT;
import convex.core.text.Text;

/**
 * Static utility class for some standard JSON utility functions
 * 
 * Note than JSON is a strict subset of CAD3 data, so we expect to encode all valid JSON perfectly in CAD3 / CVM data structures
 */
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
	 * Gets a String from a value suitable for use as a JSON map key
	 * 
	 * @param o Value to convert to a JSON key
	 * @return String usable as JSON key
	 */
	public static String jsonKey(Object o) {
		if (o instanceof ACell cell)
			return jsonKey(cell);
		
		if (o instanceof String s) return s;
		
		throw new IllegalArgumentException("Invalid type for JSON key: "+Utils.getClassName(o));
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

	/**
	 * Convert any object to JSON
	 * 
	 * @param value Value to convert to JSON, may be Java or CVM structure
	 * @return Java String containing valid JSON String
	 */
	public static String toString(Object value) {
		return toJSONString(value).toString();
	}
	
	/**
	 * Parse JSON as a CVM value. Note JSON is a subset of CVM data types, you get Strings instead of Keywords etc.
	 * @param jsonString String containing JSON5 data
	 * @return Parsed JSON value as a CVM data structure
	 */
	public static ACell parse(String jsonString) {
		return JSONReader.read(jsonString);
	}

	/**
	 * Convert any object to JSON
	 * 
	 * @param value Value to convert to JSON, may be Java or CVM structure
	 * @return CVM String containing valid JSON
	 */
	public static AString toJSONString(Object value) {
		BlobBuilder bb = new BlobBuilder();
		appendJSON(bb, value);
		return Strings.create(bb.toBlob());
	}

	private static void appendJSON(BlobBuilder bb, Object value) {
		if (value == null) {
			bb.append(Strings.NULL);
			return;
		}
		
		if (value instanceof ACell cell) {
			appendJSON(bb,cell);
			return;
		}
		
		
		if (value instanceof Map mv) {
			bb.append('{');
			int i=0;
			@SuppressWarnings("unchecked")
			Iterator<Map.Entry<Object,Object>> it = mv.entrySet().iterator();
			while (it.hasNext()) {
			    Entry<Object, Object> me = it.next();
			    if (i>0) bb.append(",");
				appendJSON(bb, jsonKey(me.getKey()));
				bb.append(':');
				appendJSON(bb, me.getValue());
			    i += 1;
			}
			
			bb.append('}');
			return;
		}
		
		// This catches Java lists
		if (value instanceof List lv) {
			bb.append('[');
			int n = lv.size();
			for (int i = 0; i < n; i++) {
				if (i>0) bb.append(',');
				appendJSON(bb, lv.get(i));
			}
			bb.append(']');
			return;
		}
		
		if (value instanceof Boolean bv) {
			bb.append(bv ? Strings.TRUE : Strings.FALSE);
			return;
		}
		
		if (value instanceof CharSequence cs) {
			bb.append('\"');
			appendCVMStringQuoted(bb, cs);
			bb.append('\"');
			return;
		}


		if (value instanceof ASymbolic cs) {
			bb.append('\"');
			appendCVMStringQuoted(bb, cs.getName().toString());
			bb.append('\"');
			return;
		}
		
		if (value instanceof Number nv) {
			if (value instanceof Double dv) {
				if (Double.isFinite(dv)) {
					bb.append(nv.toString());
					return;
				} else {
					if (Double.isNaN(dv)) {
						bb.append(JS_NAN);
					} else {
						if (dv<0) {
							bb.append('-');
						}
						bb.append("Infinity");
					}
				}
				return;
			}
			
			bb.append(nv.toString());
			return;			
		}
		
		throw new IllegalArgumentException("Can't print type as JSON: "+Utils.getClassName(value));
	}
	
	// Specialised writing for CVM types
	private static void appendJSON(BlobBuilder bb, ACell value) {
		if (value == null) {
			bb.append(Strings.NULL);
			return;
		}
		
		if (value instanceof AString cs) {
			bb.append('\"');
			appendCVMStringQuoted(bb, cs.toString()); // TODO: can be faster
			bb.append('\"');
			return;
		}
		
		if (value instanceof ASymbolic cs) {
			// Print as the symbolic name string
			appendJSON(bb, cs.getName()); 
			return;
		}
		
		// CVM map special treatment
		if (value instanceof AMap mv) {
			bb.append('{');
			long n = mv.size();
			for (long i = 0; i < n; i++) {
				if (i>0) bb.append(',');
				MapEntry<?,?> me=mv.entryAt(i);
				appendJSON(bb, jsonKey(me.getKey()));
				bb.append(':');
				appendJSON(bb, me.getValue());
			}
			bb.append('}');
			return;
		}

		// Maps, Lists and Sets get printed as JSON arrays
		if (value instanceof ACollection lv) {
			bb.append('[');
			long n = lv.count();
			for (long i = 0; i < n; i++) {
				if (i>0) bb.append(',');
				appendJSON(bb, lv.get(i));
			}
			bb.append(']');
			return;
		}

		
		if (value instanceof CVMLong nv) {
			appendJSON(bb,nv.longValue());
			return;
		}
		
		if (value instanceof ABlob bv) {
			bb.append("\"0x");
			bb.append(bv.toHexString());
			bb.append('\"');
			return;
		}
		
		if (value instanceof CVMDouble nv) {
			appendJSON(bb,nv.doubleValue());
			return;
		}
		
		if (value instanceof CVMBool bv) {
			bb.append(bv.booleanValue() ? Strings.TRUE : Strings.FALSE);
			return;
		}
		
		throw new IllegalArgumentException("Can't print as JSON: "+Utils.getClassName(value));
	}


	private static void appendCVMStringQuoted(BlobBuilder bb, CharSequence cs) {
		int n = cs.length();
		for (int i = 0; i < n; i++) {
			char c = cs.charAt(i);
			AString rep = getReplacementString(c);
			if (rep != null) {
				bb.append(rep);
			} else {
				bb.append(c);
			}
		}
	}

	private static final StringShort QUOTED_BACKSLASH = StringShort.create("\\\\");
	private static final StringShort QUOTED_QUOTES = StringShort.create("\\\"");
	private static final StringShort QUOTED_NEWLINE = StringShort.create("\\n");
	private static final StringShort QUOTED_RETURN = StringShort.create("\\r");
	private static final StringShort QUOTED_TAB = StringShort.create("\\t");
	
	private static final StringShort JS_NAN = StringShort.create("NaN");
	
	private static final char CONTROL_CHARS_END = 0x001f; // Highest ASCII control character


	private static AString getReplacementString(char c) {
		if (c == '\\') {
			return QUOTED_BACKSLASH;
		}
		if (c > '"') {
			// anything above this is OK in a JSON String
			return null;
		}
		if (c == '"') {
			return QUOTED_QUOTES;
		}
		if (c > CONTROL_CHARS_END) {
			return null;
		}
		if (c == '\n') {
			return QUOTED_NEWLINE;
		}
		if (c == '\r') {
			return QUOTED_RETURN;
		}
		if (c == '\t') {
			return QUOTED_TAB;
		}
		return StringShort.create(Blob.wrap(new byte[] { '\\', 'u', '0', '0', (byte) Utils.toHexChar((c >> 4) & 0x000f), (byte) Utils.toHexChar(c & 0x000f) }));
	}

	/**
	 * Escape a string for inclusion in JSON
	 * @param content
	 * @return CVM String containing JSON escaped content
	 */
	public static AString escape(String content) {
		BlobBuilder bb=new BlobBuilder();
		appendCVMStringQuoted(bb,content);
		return Strings.create(bb.toBlob());
	}

	
	/**
	 * Unescapes JSON string content
	 * @param content
	 * @return CVM String containing JSON unescaped content
	 */
	public static AString unescape(String content) {
		// This this works as JSON is subset of Java escaped Strings?
		String unes=Text.unescapeJava(content);
		return Strings.create(unes);
	}

}
