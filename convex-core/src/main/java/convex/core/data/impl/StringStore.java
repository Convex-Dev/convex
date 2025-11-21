package convex.core.data.impl;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;

import convex.core.data.ABlob;
import convex.core.data.AString;
import convex.core.data.ASymbolic;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Keyword;
import convex.core.data.StringShort;
import convex.core.data.Symbol;

/**
 * Internal caching for permanently interned Strings and symbolic values
 * 
 * Don't use this for anything sent in externally!
 */
public class StringStore {
	
	
	static HashMap<String,Entry> stringIndex=new HashMap<>();
	
	static HashMap<Blob,Entry> blobIndex=new HashMap<>();


	public static class Entry {
		String string=null;
		StringShort astring=null;
		Keyword keyword=null;
		Symbol symbol = null;
		Blob blob;
		
		public Entry(Blob b) {
			this.blob=b;
		}
		
		/**
		 * Gets the StringShort version of an interned String
		 * @return StringShort
		 */
		public StringShort getStringShort() {
			StringShort result=astring;
			if (result==null) {
				result=Cells.intern(StringShort.wrap(blob));
				astring = result;
			}
			return result;
		}

		/**
		 * Gets the Keyword version of an interned String
		 * @return Keyword instance, or null if not a valid Keyword
		 */
		public Keyword getKeyword() {
			Keyword result=keyword;
			if (result==null) {
				StringShort ss=getStringShort();
				if (!ASymbolic.validateName(ss)) return null;
				result=Cells.intern(Keyword.unsafeCreate(ss));
				keyword = result;
			}
			return result;
		}
		
		/**
		 * Gets the Keyword version of an interned String
		 * @return Keyword instance, or null if not a valid Keyword
		 */
		public Symbol getSymbol() {
			Symbol result=symbol;
			if (result==null) {
				StringShort ss=getStringShort();
				if (!ASymbolic.validateName(ss)) return null;
				result=Cells.intern(Symbol.unsafeCreate(ss));
				symbol = result;
			}
			return result;
		}
	}

	
	public static Entry get(String string) {
		Entry e=stringIndex.get(string);
		return e;
	}
	
	public static Entry get(AString name) {
		return get(name.toBlob());
	}
	
	public static Entry get(ABlob blob) {
		Entry e=blobIndex.get(blob);
		return e;
	}
	
	public static StringShort intern(String s) {
		Entry e=get(s);
		if (e==null) {
			Blob b=Blob.wrap(s.getBytes(StandardCharsets.UTF_8));
			if (b.count()>StringShort.MAX_LENGTH) throw new IllegalArgumentException("String too large to intern");
			e=new Entry(b);
			e.string=s;
			
			StringShort astring=StringShort.wrap(b);
			astring=Cells.intern(astring);
			e.astring=astring;
			
			stringIndex.put(s, e);
			blobIndex.put(b, e);
			
			return astring;
		} else {
			return e.getStringShort();
		}
	}
	
	public static StringShort intern(AString s) {
		if (s.count()>StringShort.MAX_LENGTH) throw new IllegalArgumentException("String too large to intern");
		Blob b=s.toFlatBlob();
		Entry e=get(b);
		if (e==null) {
			
			e=new Entry(b);
			StringShort astring=(s instanceof StringShort ss)?ss:StringShort.wrap(b);
			astring=Cells.intern(astring);
			e.astring=astring;
			
			String js=astring.toString();
			e.string=js;
			
			stringIndex.put(js, e);
			blobIndex.put(b, e);
			
			return astring;
		} else {
			return e.getStringShort();
		}
	}


}
