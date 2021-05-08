package convex.core.data;

import java.nio.ByteBuffer;
import java.util.WeakHashMap;

import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.Utils;

/**
 * <p>Class representing a Symbol. Symbols are more commonly used in CVM code to refer to functions and values in the
 * execution environment.</p>
 * 
 * <p>Symbols are simply small immutable data Objects, and can be used freely in data structures. They can be used as map
 * keys, however for most normal circumstances Strings or Keywords are more appropriate as keys.
 * </p>
 * 
 * <p>
 * A Symbol comprises:
 * - A name
 * - An optional namespace
 * </p>
 * 
 * <p>
 * A Symbol with a namespace is said to be "qualified", accordingly a Symbol with no namespace is "unqualified".
 * </p>
 *
 * <p>
 * "Becoming sufficiently familiar with something is a substitute for
 * understanding it." - John Conway
 * </p>
 */
public class Symbol extends ASymbolic {

	/**
	 * Namespace component of the Symbol. Must not itself have a namespace. May be null.
	 */
	private final ACell path;
	
	private Symbol(ACell path,AString name) {
		super(name);
		this.path=path;
	}
	
	protected static final WeakHashMap<Symbol,Symbol> cache=new WeakHashMap<>(100);

	/**
	 * Creates a Symbol with the given unqualified namespace Symbol and name
	 * @param nameSpace Namespace Symbol, which may be null for an unqualified Symbol
	 * @param name Unqualified Symbol name
	 * @return Symbol instance, or null if the Symbol is invalid
	 */
	public static Symbol create(Symbol namespace, AString name) {
		if (!validateName(name)) return null;
		if (namespace!=null) {
			// namespace can't currently be qualified itself
			if (namespace.isQualified()) return null;
		}
		Symbol sym= new Symbol(namespace,name);
		
		synchronized (cache) {
			// TODO: figure out if caching Symbols is a net win or not
			Symbol cached=cache.get(sym);
			if (cached!=null) return cached;
			cache.put(sym,sym);
		}
		
		return sym;
	}
	
	/**
	 * Creates a Symbol with the given path and name
	 * @param path Address path, which may be null for an unqualified Symbol
	 * @param name Unqualified Symbol name
	 * @return Symbol instance, or null if the Symbol is invalid
	 */
	public static Symbol create(ACell path, AString name) {
		if (!validateName(name)) return null;
		Symbol sym= new Symbol(path,name);
		
		synchronized (cache) {
			// TODO: figure out if caching Symbols is a net win or not
			Symbol cached=cache.get(sym);
			if (cached!=null) return cached;
			cache.put(sym,sym);
		}
		
		return sym;
	}
	
	/**
	 * Creates a Symbol with the given name. Must be an unqualified name.
	 * 
	 * @param name
	 * @return Symbol instance, or null if the name is invalid for a Symbol.
	 */
	public static Symbol create(String name) {
		if (name==null) return null;
		return create((Address)null,Strings.create(name));
	}
	
	/**
	 * Create an unqualified symbol with the given name.
	 * @param name A valid Symbol name.
	 * @return Symbol instance, or null if the name is invalid for a Symbol.
	 */
	public static Symbol create(AString name) {
		return create((Address)null,name);
	}
	
	/**
	 * Create an qualified symbol with the given namespace symbol.
	 * @param name A valid Symbol name.
	 * @param ns A valid Symbol name for the namespace
	 * @return Symbol instance, or null if the name or namespace is invalid for a Symbol.
	 */
	public static Symbol createWithPath(AString name, ACell ns) {
		return create(name).withPath(ns);
	}
	
	public static Symbol createWithPath(String name, String ns) {
		Symbol nsym=Symbol.create(ns);
		if (nsym==null) return null;
		return createWithPath(Strings.create(name),nsym);
	}
	
	/**
	 * Returns the namespace alias component of a Symbol, or null if not present
	 * @return Namespace Symbol or null
	 */
	public ACell getPath() {
		return path;
	}
	
	/**
	 * Returns the Symbol with an updated path
	 * @return Updated Symbol, or this Symbol if no change
	 */
	public Symbol withPath(ACell newPath) {
		if (path==newPath) return this;
		return new Symbol(newPath,name);
	}

	/**
	 * Returns the Symbol with an updated path
	 * @return Updated Symbol, or this Symbol if no change
	 */
	public Object withPath(String newPath) {
		return withPath(Symbol.create(newPath));
	}

	@Override
	public boolean equals(ACell o) {
		if (o instanceof Symbol) return equals((Symbol) o);
		return false;
	}

	/**
	 * Tests if this Symbol is equal to another Symbol. Equality is defined by both namespace and name being equal.
	 * @param sym
	 * @return
	 */
	public boolean equals(Symbol sym) {
		return sym.name.equals(name)&&Utils.equals(path,sym.getPath());
	}

	@Override
	public int hashCode() {
		return name.hashCode()+119*((path==null)?0:path.hashCode());
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.SYMBOL;
		return encodeRaw(bs,pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos = Format.write(bs,pos, path);
		pos = Format.writeRawUTF8String(bs, pos, name.toString());
		return pos;
	}

	/**
	 * Reads a Symbol from the given ByteBuffer, assuming tag already consumed
	 * 
	 * @param bb ByteBuffer source
	 * @return The Symbol read
	 * @throws BadFormatException If a Symbol could not be read correctly.
	 */
	public static Symbol read(ByteBuffer bb) throws BadFormatException {
		ACell namespace=Format.read(bb);
		String name=Format.readUTF8String(bb);
		Symbol sym = Symbol.create(namespace,Strings.create(name));
		if (sym == null) throw new BadFormatException("Can't read symbol");
		return sym;
	}
	
	public boolean isQualified() {
		return path!=null;
	}

	@Override
	public boolean isCanonical() {
		// Always canonical
		return true;
	}

	@Override
	public void ednString(StringBuilder sb) {
		print(sb);
	}
	
	@Override
	public void print(StringBuilder sb) {
		if (path!=null) {
			path.print(sb);
			sb.append('/');
		}
		sb.append(getName());
	}

	@Override
	public int estimatedEncodingSize() {
		return 50;
	}
	
	@Override
	public void validateCell() throws InvalidDataException {
		super.validateCell();
		if (path!=null) {
			if (path instanceof Symbol) {
				if (((Symbol)path).isQualified()) throw new InvalidDataException("Invalid symbol path, cannot be qualified: " + path, this);
			}
			// TODO: vector and address paths?
			path.validateCell();
		}
	}

	/**
	 * Converts to an unqualified Symbol by removing any namespace component
	 * @return An unqualified Symbol with the same name as this Symbol.
	 */
	public Symbol toUnqualified() {
		if (path==null) return this;
		return Symbol.create(getName());
	}


	@Override
	public int getRefCount() {
		return 0;
	}

	/**
	 * Returns true if the symbol starts with an asterisk '*' and is therefore potentially a special symbol.
	 * 
	 * @return True is potentially special, false otherwise.
	 */
	public boolean maybeSpecial() {
		return name.charAt(0)=='*';
	}


	@Override
	public byte getTag() {
		return Tag.SYMBOL;
	}


}
