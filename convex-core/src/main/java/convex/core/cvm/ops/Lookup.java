package convex.core.cvm.ops;

import convex.core.ErrorCodes;
import convex.core.cvm.AOp;
import convex.core.cvm.Context;
import convex.core.cvm.Juice;
import convex.core.cvm.Ops;
import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
import convex.core.data.Symbol;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;

/**
 * Op to look up a Symbol in the current execution context.
 * 
 * Holds an optional Address to specify lookup in another Account environment. If null, the Lookup will be performed in
 * the current environment.
 * 
 * Consumes juice for lookup when executed.
 *
 * @param <T> Result type of Op
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

	@Override
	public Context execute(Context context) {
		Context rctx=context;
		Address namespaceAddress=null;
		if (address!=null) {
			rctx=rctx.execute(address);
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
	public boolean print(BlobBuilder bb, long limit) {
		if (address!=null) {
			if (!address.print(bb,limit)) return false;
			bb.append('/');
		}
		return symbol.print(bb,limit);
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

	/**
	 * Reads a Lookup op from a Blob encoding
	 * @param <T> Type of Lookup value
	 * @param b Blob to read from
	 * @param pos Start position in Blob (location of tag byte)
	 * @return New decoded instance
	 * @throws BadFormatException In the event of any encoding error
	 */
	public static <T extends ACell> Lookup<T> read(Blob b,int pos) throws BadFormatException {
		int epos=pos+Ops.OP_DATA_OFFSET; // skip tag and opcode to get to data
		
		Symbol sym = Format.read(b,epos);
		if (sym==null) throw new BadFormatException("Lookup symbol cannot be null");
		epos+=Format.getEncodingLength(sym);
		
		AOp<Address> address = Format.read(b,epos);
		epos+=Format.getEncodingLength(address);
		
		Lookup<T> result= create(address,sym);
		result.attachEncoding(b.slice(pos, epos));
		return result;
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
