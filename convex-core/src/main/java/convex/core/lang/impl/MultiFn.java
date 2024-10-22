package convex.core.lang.impl;

import convex.core.cvm.AFn;
import convex.core.cvm.Context;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
import convex.core.data.Tag;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;

public class MultiFn<T extends ACell> extends AClosure<T> {

	private final AVector<AClosure<T>> fns;
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
	public boolean print(BlobBuilder bb, long limit) {
		bb.append("(fn ");
		if (!printInternal(bb,limit)) return false;;
		bb.append(')');
		return bb.check(limit);
	}
	
	@Override
	public boolean printInternal(BlobBuilder bb, long limit) {
		for (long i=0; i<num; i++) {
			if (i>0) bb.append(' ');
			bb.append('(');
			if (!fns.get(i).printInternal(bb,limit)) return false;;
			bb.append(')');
		}
		return bb.check(limit);
	}

	@Override
	public Context invoke(Context context, ACell[] args) {
		for (int i=0; i<num; i++) {
			AClosure<T> fn=fns.get(i);
			if (fn.supportsArgs(args)) {
				return fn.invoke( context, args);
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
		AVector<AClosure<T>> newFns=fns.updateRefs(func);
		if (fns==newFns) return this;
		return new MultiFn<T>(newFns);
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
	

	/**
	 * Decodes a MultiFn instance from a Blob encoding
	 * 
	 * @param b Blob to read from
	 * @param pos Start position in Blob (location of tag byte)
	 * @return New decoded instance
	 * @throws BadFormatException In the event of any encoding error
	 */
	public static <T extends ACell> MultiFn<T> read(Blob b, int pos) throws BadFormatException {
		int epos=pos+1; // skip tag
		
		AVector<AClosure<T>> fns=Format.read(b,epos);
		if (fns==null) throw new BadFormatException("Null fns!");
		epos+=Format.getEncodingLength(fns);
		
		MultiFn<T> result= new MultiFn<T>(fns);
		result.attachEncoding(b.slice(pos, epos));
		return result;
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
