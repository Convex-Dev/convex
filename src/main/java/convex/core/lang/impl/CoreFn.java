package convex.core.lang.impl;

import java.nio.ByteBuffer;

import convex.core.data.AVector;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
import convex.core.data.Symbol;
import convex.core.data.Syntax;
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
public abstract class CoreFn<T> extends AFn<T> implements ICoreDef {

	private Symbol symbol;

	protected CoreFn(Symbol symbol) {
		this.symbol = symbol;
	}

	@Override
	public abstract <I> Context<T> invoke(Context<I> context, Object[] args);

	// TODO: proper param names for core functions
	@Override
	public AVector<Syntax> getParams() {
		return null;
	}

	public Symbol getSymbol() {
		return symbol;
	}

	protected String name() {
		return symbol.getName();
	}

	@Override
	public boolean isCanonical() {
		return true;
	}

	protected String minArityMessage(int minArity, int actual) {
		return name() + " requires at minimum arity " + minArity + " but called with: " + actual;
	}

	protected String maxArityMessage(int maxArity, int actual) {
		return name() + " requires maximum arity " + maxArity + " but called with: " + actual;
	}

	protected String exactArityMessage(int arity, int actual) {
		return name() + " requires arity " + arity + " but called with: " + actual;
	}

	@Override
	public void ednString(StringBuilder sb) {
		sb.append(symbol.getName());
	}

	@Override
	public ByteBuffer write(ByteBuffer b) {
		b = b.put(Tag.CORE_DEF);
		return writeRaw(b);
	}

	@Override
	public ByteBuffer writeRaw(ByteBuffer b) {
		b = symbol.writeRaw(b);
		return b;
	}
	
	@Override
	public int getRefCount() {
		// Core functions have no Refs
		return 0;
	}
	
	@Override
	public <R> Ref<R> getRef(int i) {
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

}
