package convex.core.lang.impl;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

import convex.core.data.AVector;
import convex.core.data.Format;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
import convex.core.data.Tag;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.AFn;
import convex.core.lang.Context;

public class MultiFn<T> extends AFn<T> {

	private final AVector<Fn<T>> fns;
	private final int num;
	
	private MultiFn(AVector<Fn<T>> fns) {
		this.fns=fns;
		this.num=fns.size();
	}
	

	public static <R> MultiFn<R> create(AVector<Fn<R>> fns) {
		return new MultiFn<>(fns);
	}
	
	@Override
	public boolean isCanonical() {
		return true;
	}

	@Override
	public void ednString(StringBuilder sb) {
		sb.append("(fn");
		for (Fn<T> fn:fns) {
			sb.append(' ');
			fn.ednString(sb);
		}
		sb.append(')');
	}

	@Override
	public void print(StringBuilder sb) {
		sb.append("(fn");
		for (Fn<T> fn:fns) {
			sb.append(' ');
			fn.printParamBody(sb);
		}
		sb.append(')');
	}

	@Override
	public <I> Context<T> invoke(Context<I> context, Object[] args) {
		for (int i=0; i<num; i++) {
			Fn<T> fn=fns.get(i);
			if (fn.supportsArgs(args)) {
				return fn.invoke(context, args);
			}
		}
		return context.withArityError("No matching function arity found for arity "+args.length);
	}

	@Override
	public AFn<T> updateRefs(IRefFunction func) {
		AVector<Fn<T>> newFns=fns.updateRefs(func);
		if (fns==newFns) return this;
		return new MultiFn<T>(newFns);
	}

	@Override
	public void validateCell() throws InvalidDataException {
		fns.validateCell();
	}

	@Override
	public int write(byte[] bs, int pos) {
		bs[pos++]=Tag.FN_MULTI;
		return writeRaw(bs,pos);
	}

	@Override
	public int writeRaw(byte[] bs, int pos) {
		pos = fns.write(bs,pos);
		return pos;
	}
	
	public static <T> MultiFn<T> read(ByteBuffer bb) throws BadFormatException, BufferUnderflowException {
		AVector<Fn<T>> fns=Format.read(bb);
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
	public <R> Ref<R> getRef(int i) {
		return fns.getRef(i);
	}


}
