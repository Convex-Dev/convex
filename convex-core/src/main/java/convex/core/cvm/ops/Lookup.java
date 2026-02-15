package convex.core.cvm.ops;

import convex.core.ErrorCodes;
import convex.core.cvm.AOp;
import convex.core.cvm.Address;
import convex.core.cvm.CVMTag;
import convex.core.cvm.Context;
import convex.core.cvm.Juice;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Format;
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
public class Lookup<T extends ACell> extends ACodedOp<T,AOp<Address>,Symbol> {
	private Lookup(Ref<AOp<Address>> address,Ref<Symbol> symbol) {
		super (CVMTag.OP_LOOKUP,address,symbol);
	}

	/**
	 * Creates a Lookup op from decoded refs.
	 * @param <T> Result type
	 * @param code Code ref (address op)
	 * @param value Value ref (symbol)
	 * @return Lookup instance
	 */
	@SuppressWarnings("unchecked")
	public static <T extends ACell> Lookup<T> createFromRefs(Ref<ACell> code, Ref<ACell> value) {
		return new Lookup<>((Ref<AOp<Address>>)(Ref<?>)code, (Ref<Symbol>)(Ref<?>)value);
	}

	public static <T extends ACell> Lookup<T> create(AOp<Address> address, Symbol form) {
		return new Lookup<T>(Ref.get(address),form.getRef());
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
		AOp<Address> address=code.getValue();
		if (address!=null) {
			rctx=rctx.execute(address);
			if (rctx.isExceptional()) return rctx;
			ACell maybeAddress=rctx.getResult();
			namespaceAddress=RT.ensureAddress(maybeAddress);
			if (namespaceAddress==null) return rctx.withError(ErrorCodes.CAST,"Lookup requires Address but got: "+RT.getType(maybeAddress));
		}
		
		// Do a dynamic lookup, with address if specified or address from current context otherwise
		namespaceAddress=(address==null)?context.getAddress():namespaceAddress;
		Symbol symbol=value.getValue();
		return rctx.lookupDynamic(namespaceAddress,symbol).consumeJuice(Juice.LOOKUP_DYNAMIC);
	}

	@Override
	public boolean print(BlobBuilder bb, long limit) {
		AOp<Address> address=code.getValue();
		if (address!=null) {
			if (!address.print(bb,limit)) return false;
			bb.append('/');
		}
		Symbol symbol=value.getValue();
		return symbol.print(bb,limit);
	}



	@Override
	public void validateCell() throws InvalidDataException {
		// TODO: any checks?
	}

	public AOp<Address> getAddress() {
		return code.getValue();
	}

	@Override
	protected AOp<T> rebuild(Ref<AOp<Address>> newCode, Ref<Symbol> newValue) {
		if ((code==newCode)&&(value==newValue)) return this;
		return new Lookup<T>(newCode,newValue);
	}


}
