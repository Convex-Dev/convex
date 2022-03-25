package convex.core.lang.ops;

import java.nio.ByteBuffer;

import convex.core.data.ACell;
import convex.core.data.AList;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.BlobBuilder;
import convex.core.data.Format;
import convex.core.data.IRefFunction;
import convex.core.data.List;
import convex.core.data.Ref;
import convex.core.data.Strings;
import convex.core.data.VectorLeaf;
import convex.core.data.prim.CVMBool;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.AOp;
import convex.core.lang.Context;
import convex.core.lang.Juice;
import convex.core.lang.Ops;
import convex.core.lang.RT;
import convex.core.util.Errors;

/**
 * Operation representing a constant value
 * 
 * "One man's constant is another man's variable." - Alan Perlis
 *
 * @param <T> Type of constant value
 */
public class Constant<T extends ACell> extends AOp<T> {

	public static final Constant<?> NULL = new Constant<>(Ref.NULL_VALUE);
	public static final Constant<CVMBool> TRUE = new Constant<>(Ref.TRUE_VALUE);
	public static final Constant<CVMBool> FALSE = new Constant<>(Ref.FALSE_VALUE);
	
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static final Constant<AVector<?>> EMPTY_VECTOR = new Constant(VectorLeaf.EMPTY_REF);

	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static final Constant<AList<?>> EMPTY_LIST = new Constant(List.EMPTY_REF);

	
	private final Ref<T> valueRef;

	private Constant(Ref<T> valueRef) {
		this.valueRef = valueRef;
	}

	public static <T extends ACell> Constant<T> create(T value) {
		return new Constant<T>(Ref.get(value));
	}
	
	public static <T extends ACell> Constant<T> of(Object value) {
		return create(RT.cvm(value));
	}
	
	public static Constant<AString> createString(String stringValue) {
		return new Constant<AString>(Strings.create(stringValue).getRef());
	}

	public static <T extends ACell> Constant<T> createFromRef(Ref<T> valueRef) {
		if (valueRef == null) throw new IllegalArgumentException("Can't create with null ref");
		return new Constant<T>(valueRef);
	}

	@Override
	public <I extends ACell> Context<T> execute(Context<I> context) {
		return context.withResult(Juice.CONSTANT, valueRef.getValue());
	}
	
	@Override
	public boolean print(BlobBuilder sb, long limit) {
		return RT.print(sb,valueRef.getValue(),limit);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos = valueRef.encode(bs,pos);
		return pos;
	}
	
	@Override
	public int estimatedEncodingSize() {
		return 1+Format.MAX_EMBEDDED_LENGTH;
	}

	public static <T extends ACell> AOp<T> read(ByteBuffer bb) throws BadFormatException {
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
	public <R extends ACell> Ref<R> getRef(int i) {
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
	public static <T extends ACell> AOp<T> nil() {
		return (AOp<T>) Constant.NULL;
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (valueRef == null) throw new InvalidDataException("Missing contant value ref!", this);
	}


}
