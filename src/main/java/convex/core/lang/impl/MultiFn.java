package convex.core.lang.impl;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AVector;
import convex.core.data.Format;
import convex.core.data.IRefFunction;
import convex.core.data.Maps;
import convex.core.data.Ref;
import convex.core.data.Symbol;
import convex.core.data.Tag;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.AFn;
import convex.core.lang.Context;

public class MultiFn<T extends ACell> extends AClosure<T> {

	private final AVector<AFn<T>> fns;
	private final int num;
	
	private MultiFn(AVector<AFn<T>> fns, AHashMap<Symbol, ACell> env) {
		super(env);
		this.fns=fns;
		this.num=fns.size();
	}
	
	private MultiFn(AVector<AFn<T>> fns) {
		this(fns,Maps.empty());
	}
	

	public static <R extends ACell> MultiFn<R> create(AVector<AFn<R>> fns) {
		return new MultiFn<>(fns);
	}
	
	@Override
	public boolean isCanonical() {
		return true;
	}

	@Override
	public void ednString(StringBuilder sb) {
		sb.append("(fn");
		for (AFn<T> fn:fns) {
			sb.append(' ');
			fn.ednString(sb);
		}
		sb.append(')');
	}

	@Override
	public void print(StringBuilder sb) {
		sb.append("(fn");
		for (AFn<T> fn:fns) {
			sb.append(' ');
			fn.print(sb);
		}
		sb.append(')');
	}

	@Override
	public Context<T> invoke(Context<ACell> context, ACell[] args) {
		for (int i=0; i<num; i++) {
			AFn<T> fn=fns.get(i);
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
		AVector<AFn<T>> newFns=fns.updateRefs(func);
		if (fns==newFns) return this;
		return new MultiFn<T>(newFns);
	}

	@Override
	public void validateCell() throws InvalidDataException {
		fns.validateCell();
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.FN_MULTI;
		return encodeRaw(bs,pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos = fns.write(bs,pos);
		return pos;
	}
	
	public static <T extends ACell> MultiFn<T> read(ByteBuffer bb) throws BadFormatException, BufferUnderflowException {
		AVector<AFn<T>> fns=Format.read(bb);
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
	public <F extends AClosure<T>> F withEnvironment(AHashMap<Symbol, ACell> env) {
		if (env==this.lexicalEnv) return (F) this;
		return (F) new MultiFn(fns,env);
	}




}
