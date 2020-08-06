package convex.core.lang.ops;

import java.nio.ByteBuffer;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.Address;
import convex.core.data.Format;
import convex.core.data.IRefFunction;
import convex.core.data.MapEntry;
import convex.core.data.Ref;
import convex.core.data.Symbol;
import convex.core.data.Syntax;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.AOp;
import convex.core.lang.Context;
import convex.core.lang.Juice;
import convex.core.lang.Ops;
import convex.core.util.Errors;

/**
 * Op to look up a symbol in the current execution context.
 * 
 * Consumes juice for lookup.
 *
 * @param <T>
 */
public class Lookup<T> extends AOp<T> {
	private final Address address;
	private final Symbol symbol;

	private Lookup(Address address,Symbol symbol) {
		this.address=address;
		this.symbol = symbol;
	}

	public static <T> Lookup<T> create(Address address, Symbol form) {
		return new Lookup<T>(address,form);
	}
	
	public static <T> Lookup<T> create(Address address, String name) {
		return create(address,Symbol.create(name));
	}

	public static <T> Lookup<T> create(Symbol symbol) {
		return create(null,symbol);
	}

	public static <T> Lookup<T> create(String name) {
		return create(Symbol.create(name));
	}

	@Override
	public <I> Context<T> execute(Context<I> context) {
		MapEntry<Symbol, T> le = context.lookupLocalEntry(symbol);
		if (le != null) return context.withResult(Juice.LOOKUP, le.getValue());
		
		// TODO
		Address namespaceAddress=(address==null)?context.getAddress():address;
		MapEntry<Symbol, Syntax> de = context.lookupDynamicEntry(namespaceAddress,symbol);
		
		if (de != null) return context.withResult(Juice.LOOKUP_DYNAMIC, de.getValue().getValue());
		return context.lookupSpecial(symbol).consumeJuice(Juice.LOOKUP_DYNAMIC);
	}

	@Override
	public void ednString(StringBuilder sb) {
		sb.append(symbol.toString());
	}

	@Override
	public byte opCode() {
		return Ops.LOOKUP;
	}

	@Override
	public ByteBuffer writeRaw(ByteBuffer bb) {
		bb= Format.write(bb, symbol);
		bb= Format.write(bb, address);
		return bb;
	}

	public static <T> Lookup<T> read(ByteBuffer bb) throws BadFormatException {
		Symbol sym = Format.read(bb);
		if (sym==null) throw new BadFormatException("Lookup symbol cannot be null");
		Address address = Format.read(bb);
		return create(address,sym);
	}

	@Override
	public int getRefCount() {
		return 0;
	}

	@Override
	public <R> Ref<R> getRef(int i) {
		throw new IndexOutOfBoundsException(Errors.badIndex(i));
	}

	@SuppressWarnings("unchecked")
	@Override
	public <N extends ACell> N updateRefs(IRefFunction func) {
		return (N) this;
	}

	@SuppressWarnings("unchecked")
	@Override
	public AOp<T> specialise(AMap<Symbol, Object> binds) {
		if (binds.containsKey(symbol)) {
			// bindings cover lookup
			return Constant.create((T) binds.get(symbol));
		} else {
			return this;
		}
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (address!=null) address.validateCell();
		symbol.validateCell();
	}

	/**
	 * Gets the Account Address associated with this Lookup. May be null TODO: check this?
	 * @return Address for this Lookup
	 */
	public Address getAddress() {
		return address;
	}

}
