package convex.core.data.impl;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

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
 *
 * <p>Thread safety: interning happens from arbitrary threads (any runtime
 * {@code Strings.intern} call), while lookups happen on every
 * {@code StringShort.create}, {@code Keyword.create} and {@code Symbol.create}.
 * Lookups are lock-free reads of concurrent maps. Interning builds the entry
 * outside any lock and then publishes it under the class lock, so both
 * indexes change together and every distinct string ends up with exactly one
 * {@link Entry}; a racing loser discards its candidate. Nothing but map
 * operations runs under the lock: {@code StringShort}'s own static
 * initialiser interns, so holding the lock across value construction could
 * deadlock against class initialisation on another thread. A plain HashMap
 * here is corrupted by concurrent puts, after which lookups recurse until
 * StackOverflowError.</p>
 */
public class StringStore {

	static final ConcurrentHashMap<String,Entry> stringIndex=new ConcurrentHashMap<>();

	static final ConcurrentHashMap<Blob,Entry> blobIndex=new ConcurrentHashMap<>();


	public static class Entry {
		volatile String string=null;
		volatile StringShort astring=null;
		volatile Keyword keyword=null;
		volatile Symbol symbol = null;
		final Blob blob;

		public Entry(Blob b) {
			this.blob=b;
		}

		/**
		 * Gets the StringShort version of an interned String
		 * @return StringShort
		 */
		public StringShort getStringShort() {
			StringShort result=astring;
			if (result!=null) return result;
			StringShort fresh=Cells.intern(StringShort.wrap(blob));
			synchronized (this) {
				if (astring==null) astring=fresh;
				return astring;
			}
		}

		/**
		 * Gets the Keyword version of an interned String
		 * @return Keyword instance, or null if not a valid Keyword
		 */
		public Keyword getKeyword() {
			Keyword result=keyword;
			if (result!=null) return result;
			StringShort ss=getStringShort();
			if (!ASymbolic.validateName(ss)) return null;
			Keyword fresh=Cells.intern(Keyword.unsafeCreate(ss));
			synchronized (this) {
				if (keyword==null) keyword=fresh;
				return keyword;
			}
		}

		/**
		 * Gets the Symbol version of an interned String
		 * @return Symbol instance, or null if not a valid Symbol
		 */
		public Symbol getSymbol() {
			Symbol result=symbol;
			if (result!=null) return result;
			StringShort ss=getStringShort();
			if (!ASymbolic.validateName(ss)) return null;
			Symbol fresh=Cells.intern(Symbol.unsafeCreate(ss));
			synchronized (this) {
				if (symbol==null) symbol=fresh;
				return symbol;
			}
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
		if (e!=null) return e.getStringShort();
		Blob b=Blob.wrap(s.getBytes(StandardCharsets.UTF_8));
		if (b.count()>StringShort.MAX_LENGTH) throw new IllegalArgumentException("String too large to intern");
		Entry fresh=new Entry(b);
		fresh.string=s;
		fresh.astring=Cells.intern(StringShort.wrap(b));
		return publish(s, b, fresh).getStringShort();
	}

	public static StringShort intern(AString s) {
		if (s.count()>StringShort.MAX_LENGTH) throw new IllegalArgumentException("String too large to intern");
		Blob b=s.toFlatBlob();
		Entry e=get(b);
		if (e!=null) return e.getStringShort();
		Entry fresh=new Entry(b);
		StringShort astring=(s instanceof StringShort ss)?ss:StringShort.wrap(b);
		fresh.astring=Cells.intern(astring);
		fresh.string=astring.toString();
		return publish(fresh.string, b, fresh).getStringShort();
	}

	/**
	 * Publishes a candidate entry in both indexes unless another thread got
	 * there first, in which case the existing entry wins and the candidate is
	 * discarded. Only map operations happen under the lock.
	 */
	private static Entry publish(String s, Blob b, Entry fresh) {
		synchronized (StringStore.class) {
			Entry e=stringIndex.get(s);
			if (e==null) e=blobIndex.get(b);
			if (e!=null) return e;
			blobIndex.put(b, fresh);
			stringIndex.put(s, fresh);
			return fresh;
		}
	}

}
