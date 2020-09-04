package convex.core.lang.ops;

import java.nio.ByteBuffer;

import convex.core.data.AMap;
import convex.core.data.AVector;
import convex.core.data.Format;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
import convex.core.data.Symbol;
import convex.core.data.Syntax;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.AOp;
import convex.core.lang.Context;
import convex.core.lang.Fn;
import convex.core.lang.Juice;
import convex.core.lang.Ops;
import convex.core.util.Errors;

/**
 * Op responsible for creating a new function (closure).
 * 
 * Captures value of local variable bindings during execution.
 * 
 * Equivalent to (fn [...] ...)
 *
 * @param <T>
 */
public class Lambda<T> extends AOp<Fn<T>> {
	
	private final AVector<Syntax> params;
	private final Ref<AOp<T>> body;

	protected Lambda(AVector<Syntax> params, Ref<AOp<T>> body) {
		this.params=params;
		this.body=body;
	}

	public static <T> Lambda<T> create(AVector<Syntax> params, Ref<AOp<T>> body) {
		return new Lambda<T>(params,body);
	}

	public static <T> Lambda<T> create(AVector<Syntax> params, AOp<T> body) {
		return create(params,Ref.create(body));
	}
	
	@Override
	public Lambda<T> specialise(AMap<Symbol, Object> binds)  {
		// params overload existing bindings, so ignore
		// TODO: fix this ugly hack
		for (Object o:params) {
			if (o instanceof Symbol) {
				binds=binds.dissoc((Symbol)o);
			}
		}
		AOp<T> old=body.getValue();
		AOp<T> neww=old.specialise(binds);
		if (old==neww) return this;
		return new Lambda<T>(params,Ref.create(neww));
	}

	@Override
	public <I> Context<Fn<T>> execute(Context<I> context) {
		Fn<T> fn= Fn.create(params,body.getValue(),context);
		return context.withResult(Juice.LAMBDA,fn);
	}

	@Override
	public int getRefCount() {
		return 1;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <R> Ref<R> getRef(int i) {
		if (i==0) return (Ref<R>) body;
		throw new IndexOutOfBoundsException(Errors.badIndex(i));
	}

	@Override
	public Lambda<T> updateRefs(IRefFunction func)  {
		@SuppressWarnings("unchecked")
		Ref<AOp<T>> newBody=(Ref<AOp<T>>) func.apply(body);
		AVector<Syntax> newParams=params.updateRefs(func);
		if ((params==newParams)&&(body==newBody)) return this;
		return create(newParams,newBody);
	}

	@Override
	public void ednString(StringBuilder sb)  {
		sb.append("(fn ");
		params.ednString(sb);
		sb.append(' ');
		body.ednString(sb);
		sb.append(')');
	}
	
	@Override
	public void print(StringBuilder sb)  {
		sb.append("(fn ");
		params.print(sb);
		sb.append(' ');
		body.getValue().print(sb);
		sb.append(')');
	}

	@Override
	public byte opCode() {
		return Ops.LAMBDA;
	}

	@Override
	public ByteBuffer writeRaw(ByteBuffer b) {
		b=Format.write(b, params);
		b=body.write(b);
		return b;
	}
	
	public static <T> Lambda<T> read(ByteBuffer b) throws BadFormatException {
		AVector<Syntax> params=Format.read(b);
		Ref<AOp<T>> body=Format.readRef(b);
		return create(params,body);
	}

	@Override
	public void validateCell() throws InvalidDataException {
		params.validateCell();
	}
}
