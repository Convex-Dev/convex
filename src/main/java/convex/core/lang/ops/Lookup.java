package convex.core.lang.ops;

import java.nio.ByteBuffer;

import convex.core.ErrorCodes;
import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.data.Format;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
import convex.core.data.Symbol;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.AOp;
import convex.core.lang.Context;
import convex.core.lang.Juice;
import convex.core.lang.Ops;
import convex.core.lang.RT;

/**
 * Op to look up a Symbol in the current execution context.
 * 
 * Holds an optional Address to specify lookup in another Account environment. If null, the Lookup will be performed in
 * the current environment.
 * 
 * Consumes juice for lookup when executed.
 *
 * @param <T>
 */
public class Lookup<T extends ACell> extends AOp<T> {
	private final AOp<Address> address;
	private final Symbol symbol;

	private Lookup(AOp<Address> address,Symbol symbol) {
		this.address=address;
		this.symbol = symbol;
	}

	public static <T extends ACell> Lookup<T> create(AOp<Address> address, Symbol form) {
		return new Lookup<T>(address,form);
	}
	
	public static <T extends ACell> Lookup<T> create(AOp<Address> address, String name) {
		return create(address,Symbol.create(name));
	}
	
	public static <T extends ACell> Lookup<T> create(Address addr, Symbol sym) {
		return create(Constant.of(addr),sym);
	}

	public static <T extends ACell> Lookup<T> create(Symbol symbol) {
		return create((AOp<Address>)null,symbol);
	}

	public static <T extends ACell> Lookup<T> create(String name) {
		return create(Symbol.create(name));
	}

	@SuppressWarnings("unchecked")
	@Override
	public <I extends ACell> Context<T> execute(Context<I> context) {
		Context<T> rctx=(Context<T>) context;
		Address namespaceAddress=null;
		if (address!=null) {
			rctx=(Context<T>) rctx.execute(address);
			if (rctx.isExceptional()) return rctx;
			ACell maybeAddress=rctx.getResult();
			namespaceAddress=RT.ensureAddress(maybeAddress);
			if (namespaceAddress==null) return rctx.withError(ErrorCodes.CAST,"Lookup requires Address but got: "+RT.getType(maybeAddress));
		}
		
		// Do a dynamic lookup, with address if specified or address from current context otherwise
		namespaceAddress=(address==null)?context.getAddress():namespaceAddress;
		return rctx.lookupDynamic(namespaceAddress,symbol).consumeJuice(Juice.LOOKUP_DYNAMIC);
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
	public int encodeRaw(byte[] bs, int pos) {
		pos= symbol.encode(bs, pos);
		pos= Format.write(bs,pos, address); // might be null
		return pos;
	}

	public static <T extends ACell> Lookup<T> read(ByteBuffer bb) throws BadFormatException {
		Symbol sym = Format.read(bb);
		if (sym==null) throw new BadFormatException("Lookup symbol cannot be null");
		AOp<Address> address = Format.read(bb);
		return create(address,sym);
	}

	@Override
	public int getRefCount() {
		if (address==null) return 0;
		return address.getRefCount();
	}

	@Override
	public <R extends ACell> Ref<R> getRef(int i) {
		if (address==null) throw new IndexOutOfBoundsException();
		return address.getRef(i);
	}

	@Override
	public Lookup<T> updateRefs(IRefFunction func) {
		if (address==null) return this;
		AOp<Address> newAddress=address.updateRefs(func);
		if (address==newAddress) return this;
		return create(newAddress,symbol);
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (address!=null) address.validateCell();
		symbol.validateCell();
	}

	public AOp<Address> getAddress() {
		return address;
	}


}
