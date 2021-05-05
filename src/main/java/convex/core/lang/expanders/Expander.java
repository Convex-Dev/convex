package convex.core.lang.expanders;

import convex.core.data.ACell;
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
public class Expander extends AExpander {

	/**
	 * Expansion function of the form (fn [x e] ...)
	 */
	private final AFn<ACell> fn;

	public Expander(AFn<ACell> expansionFunction) {
		this.fn = expansionFunction;
	}

	@Override
	public boolean isCanonical() {
		return true;
	}

	/**
	 * Returns a new expander wrapping the given expansion function
	 * @param expansionFunction
	 * @return Expander instance, or null if conversion not possible
	 */
	public static Expander wrap(AFn<ACell> expansionFunction) {
		if (expansionFunction==null) return null;
		return new Expander(expansionFunction);
	}
	
	/**
	 * Returns a new expander wrapping the given expansion function
	 * @param expansionFunction
	 * @return Expander instance, or null if conversion not possible
	 */
	@SuppressWarnings("unchecked")
	public static Expander wrap(ACell expansionFunction) {
		if (expansionFunction instanceof Expander) return (Expander) expansionFunction;
		if (!(expansionFunction instanceof AFn)) return null;
		return wrap((AFn<ACell>)expansionFunction);
	}


	@Override
	public void ednString(StringBuilder sb) {
		sb.append("#expander ");
		fn.ednString(sb);
	}
	
	@Override
	public void print(StringBuilder sb) {
		sb.append("(expander ");
		fn.print(sb);
		sb.append(')');
	}

	@SuppressWarnings("unchecked")
	@Override
	public Context<Syntax> expand(ACell form, AExpander ex, Context<?> context) {
		ACell[] args = new ACell[2];
		args[0] = form;
		args[1] = ex;

		// call expansion fn
		Context<ACell> ectx = (Context<ACell>) (Object)context.invoke(fn, args);
		if (ectx.isExceptional()) return (Context<Syntax>) (Object) ectx;

		ACell er = ectx.getResult();

		Syntax exForm = Syntax.create(er);
		
		// if form is unchanged, return this expansion
		if (form == exForm) return (Context<Syntax>) (Object) ectx;
		
		// expand again with continuation expander
		return ectx.expand(exForm, ex, ex);
		
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.EXPANDER;
		return encodeRaw(bs,pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		return fn.write(bs,pos);
	}
	
	@Override
	public int estimatedEncodingSize() {
		return 2+fn.estimatedEncodingSize();
	}

	@Override
	public void validate() throws InvalidDataException {
		super.validate();
		if (fn == null) throw new InvalidDataException("Null function in Expander", this);
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
	public <R extends ACell> Ref<R> getRef(int i) {
		return fn.getRef(i);
	}

	@Override
	public Expander updateRefs(IRefFunction func) {
		AFn<ACell> newFn=fn.updateRefs(func);
		if (fn==newFn) return this;
		return new Expander(newFn);
	}


}
