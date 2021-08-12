package convex.core.lang.impl;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Format;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
import convex.core.data.Tag;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.AFn;
import convex.core.lang.Context;

public class MultiFn<T extends ACell> extends AClosure<T> {

	private AVector<AClosure<T>> fns;
	private final int num;
	
	private MultiFn(AVector<AClosure<T>> fns, AVector<ACell> env) {
		super(env);
		this.fns=fns;
		this.num=fns.size();
	}
	
	private MultiFn(AVector<AClosure<T>> fns) {
		this(fns,Context.EMPTY_BINDINGS);
	}
	

	public static <R extends ACell> MultiFn<R> create(AVector<AClosure<R>> fns) {
		return new MultiFn<>(fns);
	}
	
	@Override
	public boolean isCanonical() {
		return true;
	}
	
	@Override
	public MultiFn<T> toCanonical() {
		return this;
	}

	@Override
	public void print(StringBuilder sb) {
		sb.append("(fn ");
		printInternal(sb);
		sb.append(')');
	}
	
	@Override
	public void printInternal(StringBuilder sb) {
		for (long i=0; i<num; i++) {
			if (i>0) sb.append(' ');
			sb.append('(');
			fns.get(i).printInternal(sb);
			sb.append(')');
		}
	}

	@Override
	public Context<T> invoke(Context<ACell> context, ACell[] args) {
		for (int i=0; i<num; i++) {
			AClosure<T> fn=fns.get(i);
			if (fn.supportsArgs(args)) {
				return fn.invoke((Context<ACell>) context, args);
			}
		}
		// TODO: type specific message?
		return context.withArityError("No matching function arity found for arity "+args.length);
	}
	
	@Override
	public boolean hasArity(int n) {
		for (int i=0; i<num; i++) {
			AFn<T> fn=fns.get(i);
			if (fn.hasArity(n)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public AFn<T> updateRefs(IRefFunction func) {
		fns=fns.updateRefs(func);
		return this;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (num<=0) throw new InvalidDataException("MultiFn must contain at least one function",this);
		fns.validateCell();
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.FN_MULTI;
		return encodeRaw(bs,pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos = fns.encode(bs,pos);
		return pos;
	}
	
	public static <T extends ACell> MultiFn<T> read(ByteBuffer bb) throws BadFormatException, BufferUnderflowException {
		AVector<AClosure<T>> fns=Format.read(bb);
		if (fns==null) throw new BadFormatException("Null fns!");
		return new MultiFn<T>(fns);
	}

	@Override
	public int estimatedEncodingSize() {
		return fns.estimatedEncodingSize()+1;
	}

	@Override
	public int getRefCount() {
		return fns.getRefCount();
	}
	
	@Override
	public <R extends ACell> Ref<R> getRef(int i) {
		return fns.getRef(i);
	}


	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Override
	public <F extends AClosure<T>> F withEnvironment(AVector<ACell> env) {
		// TODO: Can make environment update more efficient?
		if (env==this.lexicalEnv) return (F) this;
		return (F) new MultiFn(fns.map(fn->fn.withEnvironment(env)),env);
	}








}
