package convex.core.lang.impl;

import convex.core.data.ACell;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
import convex.core.data.Symbol;
import convex.core.data.Tag;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.AFn;
import convex.core.lang.Context;

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
	private boolean variadic;

	protected CoreFn(Symbol symbol) {
		this.symbol = symbol;
		this.arity=0;
		this.variadic=true;
	}

	@Override
	public abstract Context<T> invoke(Context<ACell> context, ACell[] args);

	public Symbol getSymbol() {
		return symbol;
	}
	
	public byte getTag() {
		return Tag.CORE_DEF;
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
		bs[pos++]=Tag.CORE_DEF;
		return encodeRaw(bs,pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos = symbol.encodeRaw(bs,pos);
		return pos;
	}
	
	@Override
	public int getRefCount() {
		// Core functions have no Refs
		return 0;
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
		return 20;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		symbol.validateCell();
	}
	
	@Override
	public boolean isEmbedded() {
		// embed core functions, since they are the same size as small symbols
		return true;
	}

}
