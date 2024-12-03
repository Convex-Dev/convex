package convex.core.lang.impl;

import convex.core.cvm.Context;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Format;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;

/**
 * Function with multiple function bodies of possible varying arities
 * @param <T>
 */
public class MultiFn<T extends ACell> extends AClosure<T> {

	private final int num;
	
	@SuppressWarnings("unchecked")
	private MultiFn(AVector<?> fns) {
		super((AVector<ACell>) fns);
		this.num=fns.size();
	}
	
	@SuppressWarnings("unchecked")
	public static <R extends ACell> MultiFn<R> create(AVector<?> data) {
		return new MultiFn<R>((AVector<AClosure<R>>) data);
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
		AVector<AClosure<T>> fns=getFunctions();
		for (long i=0; i<num; i++) {
			if (i>0) bb.append(' ');
			AClosure<?> fn=Fn.ensureFunction(fns.get(i));
			if (fn==null) {
				bb.append("nil");
				if (!bb.check(limit)) return false;
			} else {
				bb.append('(');
				if (!fn.printInternal(bb,limit)) return false;
				bb.append(')');
			}
		}
		return bb.check(limit);
	}

	@SuppressWarnings("unchecked")
	private AVector<AClosure<T>> getFunctions() {
		// TODO Auto-generated method stub
		return (AVector<AClosure<T>>)(AVector<?>)data;
	}

	@Override
	public Context invoke(Context context, ACell[] args) {
		AVector<AClosure<T>> fns=getFunctions();
		for (int i=0; i<num; i++) {
			AClosure<T> fn=Fn.ensureFunction(fns.get(i));
			if (fn==null) continue;
			if (fn.supportsArgs(args)) {
				return fn.invoke( context, args);
			}
		}
		// TODO: type specific message?
		return context.withArityError("No matching function arity found for arity "+args.length);
	}
	
	@Override
	public boolean hasArity(int n) {
		AVector<AClosure<T>> fns=getFunctions();
		for (int i=0; i<num; i++) {
			AClosure<T> fn=Fn.ensureFunction(fns.get(i));
			if (fn==null) continue;
			if (fn.hasArity(n)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		// nothing to do?
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
		epos+=Cells.getEncodingLength(fns);
		
		MultiFn<T> result= new MultiFn<T>(fns);
		result.attachEncoding(b.slice(pos, epos));
		return result;
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Override
	public <F extends AClosure<T>> F withEnvironment(AVector<ACell> env) {
		return (F) new MultiFn(data.map(a->{
			AClosure<T> fn=Fn.ensureFunction(a);
			if (fn==null) return null;
			return fn.withEnvironment(env);
		}));
	}

	@Override
	protected MultiFn<T> recreate(AVector<ACell> newData) {
		if (data==newData) return this;
		return new MultiFn<>(newData);
	}









}
