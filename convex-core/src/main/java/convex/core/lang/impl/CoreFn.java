package convex.core.lang.impl;

import convex.core.cvm.AFn;
import convex.core.cvm.CVMTag;
import convex.core.cvm.Context;
import convex.core.data.ACell;
import convex.core.data.Cells;
import convex.core.data.Format;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
import convex.core.data.Symbol;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.InvalidDataException;
import convex.core.util.Bits;

/**
 * Abstract base class for core language functions implemented in the Runtime
 * 
 * Core functions are tagged using their symbols in on-chain representation
 *
 * @param <T> Type of function result
 */
public abstract class CoreFn<T extends ACell> extends AFn<T> implements ICoreDef {

	private Symbol symbol;
	private int arity;
	private int code;
	private boolean variadic;

	protected CoreFn(Symbol symbol, int code) {
		this.symbol = symbol;
		this.arity=0;
		this.code=code;
		this.variadic=true;
	}

	@Override
	public abstract Context invoke(Context context, ACell[] args);

	@Override
	public Symbol getSymbol() {
		return symbol;
	}
	
	@Override
	public Symbol getIntrinsicSymbol() {
		// TODO should we have forms like #%count ?
		return symbol;
	}
	
	public byte getTag() {
		return CVMTag.CORE_DEF;
	}

	protected String name() {
		return symbol.getName().toString();
	}

	@Override
	public boolean isCanonical() {
		return true;
	}
	
	@Override
	public CoreFn<T> toCanonical() {
		return this;
	}

	protected String minArityMessage(int minArity, int actual) {
		return name() + " requires minimum arity " + minArity + " but called with: " + actual;
	}

	protected String maxArityMessage(int maxArity, int actual) {
		return name() + " requires maximum arity " + maxArity + " but called with: " + actual;
	}
	
	protected String rangeArityMessage(int minArity, int maxArity, int actual) {
		return name() + " requires arity between "+minArity+ " and " + maxArity + " but called with: " + actual;
	}

	protected String exactArityMessage(int arity, int actual) {
		return name() + " requires arity " + arity + " but called with: " + actual;
	}
	
	@Override
	public boolean hasArity(int n) {
		if (n==arity) return true;
		if (n<arity) return false;
		return variadic;
	}
	
	@Override
	public boolean print(BlobBuilder sb, long limit) {
		return symbol.print(sb,limit);
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=CVMTag.CORE_DEF;
		return encodeRaw(bs,pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos = Format.writeVLQCount(bs, pos, code);
		return pos;
	}
	
	@Override
	public int getRefCount() {
		// Core functions have no Refs
		return 0;
	}
	
	@Override
	public final int hashCode() {
		// This is needed to match hash behaviour of extension values
		return Bits.hash32(code);
	}
	
	@Override
	public <R extends ACell> Ref<R> getRef(int i) {
		throw new IndexOutOfBoundsException("Bad ref index: "+i);
	}
	
	@Override
	public CoreFn<T> updateRefs(IRefFunction func) {
		return this;
	}

	@Override
	public int estimatedEncodingSize() {
		return 5;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		symbol.validateCell();
	}
	
	@Override
	public boolean isEmbedded() {
		// core functions are always small embedded values
		return true;
	}
	
	@Override
	protected final long calcMemorySize() {	
		// always embedded and no child Refs, so memory size == 0
		return 0;
	}
	
	@Override 
	public boolean equals(ACell o) {
		// This is OK since these are guaranteed to be singleton instances!
		if (o instanceof CoreFn) return o==this;
		return Cells.equalsGeneric(this, o);
	}
	
	@Override
	public int getCoreCode() {
		return code;
	}

}
