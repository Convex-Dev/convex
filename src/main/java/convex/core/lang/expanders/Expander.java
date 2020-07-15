package convex.core.lang.expanders;

import java.nio.ByteBuffer;

import convex.core.data.IRefContainer;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
import convex.core.data.Syntax;
import convex.core.data.Tag;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.AFn;
import convex.core.lang.Context;

/**
 * Expander object wrapping an expansion function of the form (fn [x e] ....)
 * 
 * Expands according to the function, then calls continuation expander.
 * 
 * From Expansion Passing Style paper: (define macro-to-expander (lambda (m)
 * (lambda (x e) (e (m x) e)))).
 * 
 * "Code generation, like drinking alcohol, is good in moderation." - Alex Lowe
 */
public class Expander extends AExpander implements IRefContainer {

	/**
	 * Expansion function of the form (fn [x e] ...)
	 */
	private final AFn<Object> fn;

	public Expander(AFn<Object> expansionFunction) {
		this.fn = expansionFunction;
	}

	@Override
	public boolean isCanonical() {
		return true;
	}

	public static Expander wrap(AFn<Object> expansionFunction) {
		return new Expander(expansionFunction);
	}

	@Override
	public void ednString(StringBuilder sb) {
		sb.append("#expander ");
		fn.ednString(sb);
	}

	@SuppressWarnings("unchecked")
	@Override
	public Context<Syntax> expand(Object form, AExpander ex, Context<?> context) {
		Object[] args = new Object[2];
		args[0] = form;
		args[1] = ex;

		// call expansion fn
		Context<Object> ectx = (Context<Object>) (Object) context.invoke(fn, args);
		if (ectx.isExceptional()) return (Context<Syntax>) (Object) ectx;

		Object er = ectx.getResult();

		Syntax exForm = Syntax.create(er);
		
		// if form is unchanged, return this expansion
		if (form == exForm) return (Context<Syntax>) (Object) ectx;
		
		// expand again with continuation expander
		return ectx.expand(exForm, ex, ex);
		
	}

	@Override
	public ByteBuffer write(ByteBuffer bb) {
		bb.put(Tag.EXPANDER);
		return writeRaw(bb);
	}

	@Override
	public ByteBuffer writeRaw(ByteBuffer bb) {
		return fn.write(bb);
	}

	@Override
	public void validate() throws InvalidDataException {
		super.validate();
		if (fn == null) throw new InvalidDataException("Null function in Expander", this);
	}

	@Override
	public int estimatedEncodingSize() {
		return 100;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (fn == null) throw new InvalidDataException("Null function in Expander", this);
		fn.validateCell();
	}

	@Override
	public int getRefCount() {
		return fn.getRefCount();
	}

	@Override
	public <R> Ref<R> getRef(int i) {
		return fn.getRef(i);
	}

	@SuppressWarnings("unchecked")
	@Override
	public Expander updateRefs(IRefFunction func) {
		AFn<Object> newFn=fn.updateRefs(func);
		if (fn==newFn) return this;
		return new Expander(newFn);
	}

}
