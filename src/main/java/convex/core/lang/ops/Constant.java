package convex.core.lang.ops;

import java.nio.ByteBuffer;

import convex.core.data.AList;
import convex.core.data.AMap;
import convex.core.data.AVector;
import convex.core.data.Format;
import convex.core.data.IRefFunction;
import convex.core.data.Ref;
import convex.core.data.Symbol;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.AOp;
import convex.core.lang.Context;
import convex.core.lang.Juice;
import convex.core.lang.Ops;
import convex.core.util.Errors;
import convex.core.util.Utils;

/**
 * Operation representing a constant value
 * 
 * "One man's constant is another man's variable." - Alan Perlis
 *
 * @param <T> Type of constant value
 */
public class Constant<T> extends AOp<T> {

	public static final Constant<?> NULL = new Constant<>(Ref.NULL_VALUE);
	public static final Constant<Boolean> TRUE = new Constant<>(Ref.TRUE_VALUE);
	public static final Constant<Boolean> FALSE = new Constant<>(Ref.FALSE_VALUE);
	public static final Constant<AList<?>> EMPTY_LIST = new Constant<>(Ref.EMPTY_LIST);
	public static final Constant<AVector<?>> EMPTY_VECTOR = new Constant<>(Ref.EMPTY_VECTOR);

	private final Ref<T> valueRef;

	private Constant(Ref<T> valueRef) {
		this.valueRef = valueRef;
	}

	public static <T> Constant<T> create(T value) {
		return new Constant<T>(Ref.create(value));
	}

	public static <T> Constant<T> createFromRef(Ref<T> valueRef) {
		if (valueRef == null) throw new IllegalArgumentException("Can't create with null ref");
		return new Constant<T>(valueRef);
	}

	@Override
	public <I> Context<T> execute(Context<I> context) {
		return context.withResult(Juice.CONSTANT, valueRef.getValue());
	}

	@Override
	public void ednString(StringBuilder sb) {
		Utils.ednString(sb,valueRef.getValue());
	}
	
	@Override
	public void print(StringBuilder sb) {
		Utils.print(sb,valueRef.getValue());
	}

	@Override
	public ByteBuffer writeRaw(ByteBuffer bb) {
		bb = valueRef.write(bb);
		return bb;
	}

	public static <T> AOp<T> read(ByteBuffer bb) throws BadFormatException {
		Ref<T> ref = Format.readRef(bb);
		return createFromRef(ref);
	}

	@Override
	public byte opCode() {
		return Ops.CONSTANT;
	}

	@Override
	public int getRefCount() {
		return 1;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <R> Ref<R> getRef(int i) {
		if (i != 0) throw new IndexOutOfBoundsException(Errors.badIndex(i));
		return (Ref<R>) valueRef;
	}

	@Override
	public Constant<T> updateRefs(IRefFunction func) {
		@SuppressWarnings("unchecked")
		Ref<T> newRef = (Ref<T>) func.apply(valueRef);
		if (valueRef == newRef) return this;
		return createFromRef(newRef);
	}

	@SuppressWarnings("unchecked")
	public static <T> AOp<T> nil() {
		return (AOp<T>) Constant.NULL;
	}

	@Override
	public AOp<T> specialise(AMap<Symbol, Object> binds) {
		// constants never affected by specialisation
		return this;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (valueRef == null) throw new InvalidDataException("Missing contant value ref!", this);
	}
}
