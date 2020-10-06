package convex.core.lang.impl;

import java.nio.ByteBuffer;

import convex.core.data.AHashMap;
import convex.core.data.AVector;
import convex.core.data.Format;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
import convex.core.data.Symbol;
import convex.core.data.Syntax;
import convex.core.data.Tag;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.AFn;
import convex.core.lang.AOp;
import convex.core.lang.Context;

/**
 * Value class representing a instantiated closure / lambda function.
 * 
 * Includes the following information:
 * <ol>
 * <li>Parameters of the function, as a vector of Syntax objects</li>
 * <li>Body of the function, as a compiled operation</li>
 * <li>captured lexical bindings at time of creation.</li>
 * </ol>
 * 
 * @param <T> Return type of function
 */
public class Fn<T> extends AFn<T> {

	// note: embedding these fields directly for efficiency rather than going by
	// Refs.

	private final AVector<Syntax> params;
	private final AOp<T> body;
	private final AHashMap<Symbol, Object> lexicalEnv;

	private Fn(AVector<Syntax> params, AOp<T> body, AHashMap<Symbol, Object> lexicalEnv) {
		this.params = params;
		this.body = body;
		this.lexicalEnv = lexicalEnv;
	}

	public static <T, I> Fn<T> create(AVector<Syntax> params, AOp<T> body, Context<I> context) {
		AHashMap<Symbol, Object> binds = context.getLocalBindings();

		return new Fn<T>(params, body, binds);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <I> Context<T> invoke(Context<I> context, Object[] args) {
		int n=args.length;
		if (!hasArity(n)) return context.withArityError("Function arity not supported: "+args);
		
		// update local bindings for the duration of this function call
		final AHashMap<Symbol, Object> savedBindings = context.getLocalBindings();
		int initialDepth = context.getDepth();

		// update to correct lexical environment, then bind function parameters
		context = context.withLocalBindings(lexicalEnv);
		Context<?> boundContext = context.updateBindings(params, args);
		if (boundContext.isExceptional()) return boundContext.withLocalBindings(savedBindings);

		Context<T> rc = boundContext.execute(body);
		if (rc.isExceptional()) {
			Object v = rc.getExceptional();

			// recur as many times as needed
			while (v instanceof RecurValue) {
				// restore depth, since we are catching an exceptional
				rc = rc.withDepth(initialDepth);

				RecurValue rv = (RecurValue) v;
				Object[] newArgs = rv.getValues();

				// clear result to ensure no longer exceptional
				rc = rc.withResult(null);

				rc = rc.updateBindings(params, newArgs);
				if (rc.isExceptional()) break; // might be arity error?

				rc = rc.execute(body);
				v = rc.getValue();
			}

			// unwrap return value if necessary
			if (v instanceof ReturnValue) {
				T o = ((ReturnValue<T>) v).getValue();
				if (o instanceof AExceptional) {
					// return exceptional value
					rc = rc.withException((AExceptional) o);
				} else {
					// normal result, need to restore depth since catching an exceptional
					rc = rc.withResult(o).withDepth(initialDepth);
				}
			}
		}

		// return with restored bindings
		return rc.withLocalBindings(savedBindings);
	}

	@Override
	public boolean isCanonical() {
		return false;
	}

	@Override
	public ByteBuffer write(ByteBuffer b) {
		b = b.put(Tag.FN);
		return writeRaw(b);
	}

	@Override
	public ByteBuffer writeRaw(ByteBuffer bb) {
		bb = params.write(bb);
		bb = body.write(bb);
		bb = lexicalEnv.write(bb);
		return bb;
	}

	public static <T> Fn<T> read(ByteBuffer bb) throws BadFormatException {
		try {
			AVector<Syntax> params = Format.read(bb);
			if (params==null) throw new BadFormatException("Null parameters to Fn");
			AOp<T> body = Format.read(bb);
			if (body==null) throw new BadFormatException("Null body in Fn");
			AHashMap<Symbol, Object> lexicalEnv = Format.read(bb);
			return new Fn<>(params, body, lexicalEnv);
		} catch (ClassCastException e) {
			throw new BadFormatException("Bad Fn format", e);
		}
	}

	@Override
	public void ednString(StringBuilder sb) {
		sb.append("(fn ");
		params.ednString(sb);
		sb.append(' ');
		body.ednString(sb);
		sb.append(')');
	}
	
	@Override
	public void print(StringBuilder sb) {
		sb.append("(fn ");
		printParamBody(sb);
		sb.append(')');
	}
	
	public void printParamBody(StringBuilder sb) {
		params.print(sb);
		sb.append(' ');
		body.print(sb);
		sb.append(')');
	}

	@Override
	public int estimatedEncodingSize() {
		return 100;
	}

	/**
	 * Returns the declared param names for a function.
	 * 
	 * @return A binding vector describing the parameters for this function
	 */
	public AVector<Syntax> getParams() {
		return params;
	}

	public AOp<T> getBody() {
		return body;
	}

	@Override
	public int getRefCount() {
		return params.getRefCount() + body.getRefCount() + lexicalEnv.getRefCount();
	}

	@Override
	public <R> Ref<R> getRef(int i) {
		int pc = params.getRefCount();
		if (i < pc) return params.getRef(i);
		i -= pc;
		int bc = body.getRefCount();
		if (i < bc) return body.getRef(i);
		i -= bc;
		return lexicalEnv.getRef(i);
	}

	@Override
	public Fn<T> updateRefs(IRefFunction func) {
		AVector<Syntax> newParams = params.updateRefs(func);
		AOp<T> newBody = body.updateRefs(func);
		AHashMap<Symbol, Object> newLexicalEnv = lexicalEnv.updateRefs(func);
		if ((params == newParams) && (body == newBody) && (lexicalEnv == newLexicalEnv)) return this;
		return new Fn<>(newParams, newBody, lexicalEnv);
	}

	@Override
	public void validateCell() throws InvalidDataException {
		params.validateCell();
		body.validateCell();
		lexicalEnv.validateCell();
	}

}
