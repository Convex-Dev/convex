package convex.core.lang.ops;

import java.nio.ByteBuffer;

import convex.core.data.AVector;
import convex.core.data.Format;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
import convex.core.data.Syntax;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.AOp;
import convex.core.lang.Context;
import convex.core.lang.Juice;
import convex.core.lang.Ops;
import convex.core.lang.impl.AClosure;
import convex.core.lang.impl.Fn;
import convex.core.util.Errors;
import convex.core.util.Utils;

/**
 * Op responsible for creating a new function (closure).
 * 
 * Captures value of local variable bindings during execution.
 * 
 * Equivalent to (fn [...] ...)
 *
 * @param <T>
 */
public class Lambda<T> extends AOp<AClosure<T>> {
	
	private Ref<AClosure<T>> function;

	protected Lambda(Ref<AClosure<T>> newFunction) {
		this.function=newFunction;
	}
	
	public static <T> Lambda<T> create(AVector<Syntax> params, AOp<T> body) {
		return new Lambda<T>(Fn.create(params,body).getRef());
	}

	public static <T> Lambda<T> create(AClosure<T> fn) {
		return new Lambda<T>(fn.getRef());
	}

	@Override
	public <I> Context<AClosure<T>> execute(Context<I> context) {
		AClosure<T> fn= function.getValue().withEnvironment(context.getLocalBindings());
		return context.withResult(Juice.LAMBDA,fn);
	}

	@Override
	public int getRefCount() {
		return 1;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <R> Ref<R> getRef(int i) {
		if (i==0) return (Ref<R>) function;
		throw new IndexOutOfBoundsException(Errors.badIndex(i));
	}

	@Override
	public Lambda<T> updateRefs(IRefFunction func)  {
		@SuppressWarnings("unchecked")
		Ref<AClosure<T>> newFunction=(Ref<AClosure<T>>) func.apply(function);
		if (function==newFunction) return this;
		return new Lambda<T>(newFunction);
	}

	@Override
	public void ednString(StringBuilder sb)  {
		function.getValue().ednString(sb);
	}
	
	@Override
	public void print(StringBuilder sb)  {
		function.getValue().print(sb);
	}

	@Override
	public byte opCode() {
		return Ops.LAMBDA;
	}

	@Override
	public int writeRaw(byte[] bs, int pos) {
		pos=function.write(bs, pos);
		return pos;
	}
	
	public static <T> Lambda<T> read(ByteBuffer bb) throws BadFormatException {
		Ref<AClosure<T>> function=Format.readRef(bb);
		return new Lambda<T>(function);
	}
	
	@Override 
	public void validate() throws InvalidDataException {
		super.validate();
		
		Object fn=function.getValue();
		if (!(fn instanceof AClosure)) {
			throw new InvalidDataException("Lambda child must be a closure but got: "+Utils.getClassName(fn),this);
		}
	}

	@Override
	public void validateCell() throws InvalidDataException {
		// nothing to do?
	}

	public AClosure<T> getFunction() {
		return function.getValue();
	}
}
