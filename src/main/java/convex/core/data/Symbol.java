package convex.core.data;

import java.nio.ByteBuffer;

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

	private final Symbol namespace;
	
	private Symbol(Symbol ns,String name) {
		super(name);
		this.namespace=ns;
	}

	/**
	 * Creates a Symbol with the given unqualified namespace Symbol and name
	 * @param nameSpace Namespace Symbol, which may be null for an unqualified Symbol
	 * @param name Unqualified Symbol name
	 * @return Symbol instance, or null if the Symbol is invalid
	 */
	public static Symbol create(Symbol namespace, String name) {
		if (!validateName(name)) return null;
		if (namespace!=null) {
			// namespace can't currently be qualified itself
			if (namespace.isQualified()) return null;
		}
		return new Symbol(namespace,name);
	}
	
	/**
	 * Creates a Symbol with the given name. Must be an unqualified name.
	 * 
	 * @param name
	 * @return Symbol instance, or null if the name is invalid for a Symbol.
	 */
	public static Symbol create(String name) {
		return create(null,name);
	}
	
	public static Symbol createWithNamespace(String name, String ns) {
		return create(Symbol.create(ns),name);
	}
	
	/**
	 * Returns the namespace alias component of a Symbol, or null if not present
	 * @return Namespace Symbol or null
	 */
	public Symbol getNamespace() {
		return namespace;
	}

	public String getName() {
		return name;
	}

	@Override
	public boolean equals(Object o) {
		if (o instanceof Symbol) return equals((Symbol) o);
		return false;
	}

	/**
	 * Tests if this Symbol is equal to another Symbol. Equality is defined by both namespace and name being equal.
	 * @param sym
	 * @return
	 */
	public boolean equals(Symbol sym) {
		return sym.name.equals(name)&&Utils.equals(namespace,sym.getNamespace());
	}

	@Override
	public int hashCode() {
		return name.hashCode();
	}

	@Override
	public ByteBuffer write(ByteBuffer bb) {
		bb = bb.put(Tag.SYMBOL);
		return writeRaw(bb);
	}

	@Override
	public ByteBuffer writeRaw(ByteBuffer bb) {
		bb = Format.write(bb, namespace);
		bb = Format.writeRawString(bb, name);
		return bb;
	}

	/**
	 * Reads a Symbol from the given ByteBuffer, assuming tag already consumed
	 * 
	 * @param bb ByteBuffer source
	 * @return The Symbol read
	 * @throws BadFormatException If a Symbol could not be read correctly.
	 */
	public static Symbol read(ByteBuffer bb) throws BadFormatException {
		Symbol namespace=Format.read(bb);
		String name=Format.readString(bb);
		Symbol sym = Symbol.create(namespace,name);
		if (sym == null) throw new BadFormatException("Can't read symbol");
		return sym;
	}
	
	public boolean isQualified() {
		return namespace!=null;
	}

	@Override
	public boolean isCanonical() {
		// Always canonical
		return true;
	}

	@Override
	public void ednString(StringBuilder sb) {
		if (namespace!=null) {
			namespace.ednString(sb);
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
		if (namespace!=null) {
			if (namespace.isQualified()) throw new InvalidDataException("Invalid namespace, cannot be qualified: " + namespace, this);
			namespace.validateCell();
		}
	}

	/**
	 * Converts to an unqualified Symbol by removing any namespace component
	 * @return An unqualified Symbol with the same name as this Symbol.
	 */
	public Symbol toUnqualified() {
		if (namespace==null) return this;
		return Symbol.create(getName());
	}

}
