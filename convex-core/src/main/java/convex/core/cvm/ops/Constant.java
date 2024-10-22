package convex.core.cvm.ops;

import convex.core.cvm.AOp;
import convex.core.cvm.Context;
import convex.core.cvm.Juice;
import convex.core.cvm.Ops;
import convex.core.data.ACell;
import convex.core.data.AList;
import convex.core.data.AMap;
import convex.core.data.ASet;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.IRefFunction;
import convex.core.data.List;
import convex.core.data.Maps;
import convex.core.data.Ref;
import convex.core.data.Sets;
import convex.core.data.StringShort;
import convex.core.data.Strings;
import convex.core.data.VectorLeaf;
import convex.core.data.prim.CVMBool;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;
import convex.core.util.ErrorMessages;

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
	
	public static final Constant<StringShort> EMPTY_STRING = new Constant<>(Strings.EMPTY_REF);
	
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static final Constant<AVector<?>> EMPTY_VECTOR = new Constant(VectorLeaf.EMPTY_REF);

	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static final Constant<AList<?>> EMPTY_LIST = new Constant(List.EMPTY_REF);

	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static final Constant<AMap<?,?>> EMPTY_MAP = new Constant(Maps.EMPTY_REF);

	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static final Constant<ASet<?>> EMPTY_SET = new Constant(Sets.EMPTY_REF);

	private final Ref<T> valueRef;

	private Constant(Ref<T> valueRef) {
		this.valueRef = valueRef;
	}

	@SuppressWarnings("unchecked")
	public static <T extends ACell> Constant<T> create(T value) {
		if (value==null) return (Constant<T>) NULL;
 		return new Constant<T>(value.getRef());
	}
	
	public static <T extends ACell> Constant<T> of(Object value) {
		return create(RT.cvm(value));
	}
	
	public static <T extends ACell> Constant<T> of(T value) {
		return create(value);
	}
	
	public static Constant<CVMBool> forBoolean(boolean value) {
		return value?TRUE:FALSE;
	}
	
	public static Constant<AString> createString(String stringValue) {
		return new Constant<AString>(Strings.create(stringValue).getRef());
	}

	public static <T extends ACell> Constant<T> createFromRef(Ref<T> valueRef) {
		if (valueRef == null) throw new IllegalArgumentException("Can't create with null ref");
		return new Constant<T>(valueRef);
	}

	@Override
	public Context execute(Context context) {
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

	public static <T extends ACell> Constant<T> read(Blob b, int pos) throws BadFormatException {
		int epos=pos+Ops.OP_DATA_OFFSET; // skip tag and opcode to get to data

		Ref<T> ref = Format.readRef(b,epos);
		epos+=ref.getEncodingLength();
		Constant<T> result= createFromRef(ref);
		result.attachEncoding(b.slice(pos, epos));
		return result;
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
		if (i != 0) throw new IndexOutOfBoundsException(ErrorMessages.badIndex(i));
		return (Ref<R>) valueRef;
	}
	
	public T getValue() {
		return valueRef.getValue();
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
