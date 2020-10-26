package convex.core.lang.ops;

import java.nio.ByteBuffer;

import convex.core.data.Address;
import convex.core.data.Format;
import convex.core.data.IRefFunction;
import convex.core.data.MapEntry;
import convex.core.data.Ref;
import convex.core.data.Symbol;
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
		
		// Do a dynamic lookup, with address if specified or address from current context otherwise
		Address namespaceAddress=(address==null)?context.getAddress():address;
		return context.lookupDynamic(namespaceAddress,symbol).consumeJuice(Juice.LOOKUP_DYNAMIC);
	}

	@Override
	public void ednString(StringBuilder sb) {
		symbol.ednString(sb);
	}
	
	@Override
	public void print(StringBuilder sb) {
		symbol.print(sb);
	}

	@Override
	public byte opCode() {
		return Ops.LOOKUP;
	}

	@Override
	public int writeRaw(byte[] bs, int pos) {
		pos= symbol.write(bs, pos);
		pos= Format.write(bs,pos, address); // might be null
		return pos;
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

	@Override
	public Lookup<T> updateRefs(IRefFunction func) {
		return this;
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
