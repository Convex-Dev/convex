package convex.core.data;

import java.nio.ByteBuffer;

import convex.core.data.prim.CVMLong;
import convex.core.data.type.AType;
import convex.core.data.type.Types;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;
import convex.core.util.Utils;

/**
 * Class representing a Syntax Object.
 * 
 * A Syntax Object wraps:
 * <ul>
 * <li>A Form (which may contain nested Syntax Objects)</li>
 * <li>Metadata for the Syntax Object, which may be any arbitrary hashmap</li>
 * </ul>
 * 
 * Syntax Objects may not wrap another Syntax Object directly, but may contain nested
 * Syntax Objects within data structures.
 * 
 * Inspired by Racket.
 * 
 */
public class Syntax extends ACell {
	public static final Syntax EMPTY = create(null, null);

	/**
	 * Ref to the unwrapped datum value. Cannot refer to another Syntax object
	 */
	private final Ref<ACell> datumRef;

	/** 
	 * Metadata map
	 * If empty, gets encoded as null in byte encoding
	 */
	private final AHashMap<ACell, ACell> meta;

	private Syntax(Ref<ACell> datumRef, AHashMap<ACell, ACell> props) {
		this.datumRef = datumRef;
		this.meta = props;
	}
	
	public AType getType() {
		return Types.SYNTAX;
	}
	
	public static Syntax createUnchecked(ACell value, AHashMap<ACell, ACell> meta) {
		return new Syntax(Ref.get(value),meta);
	}

	/**
	 * Wraps a value as a Syntax Object, adding the given new metadata
	 * 
	 * @param value Value to wrap in Syntax Object
	 * @param meta Metadata to merge, may be null
	 * @return Syntax instance
	 */
	public static Syntax create(ACell value, AHashMap<ACell, ACell> meta) {
		if (value instanceof Syntax) {
			Syntax stx=((Syntax) value);
			if (meta==null) return stx;
			return stx.mergeMeta(meta);
		}
		if (meta==null) meta=Maps.empty();
		
		return new Syntax(Ref.get(value), meta);
	}

	/**
	 * Wraps a value as a Syntax Object with empty metadata. Does not change existing Syntax objects.
	 * 
	 * @param value Any CVM value
	 * @return Syntax instance
	 */
	public static Syntax create(ACell value) {
		if (value instanceof Syntax) return (Syntax) value;
		return create(value, Maps.empty());
	}
	
	/**
	 * Wraps a value as a Syntax Object with empty metadata. Does not change existing Syntax objects.
	 * 
	 * @param value Any value, will be converted to valid CVM type
	 * @return Syntax instance
	 */
	public static Syntax of(ACell value) {
		return create(value);
	}
	
	/**
	 * Create a Syntax Object with the given value. Converts to appropriate CVM type as a convenience
	 * 
	 * @param value
	 * @return Syntax instance
	 */
	public static Syntax of(Object value) {
		return create(RT.cvm(value));
	}

	/**
	 * Gets the value datum from this Syntax Object
	 * 
	 * @param <R> Expected datum type from Syntax object
	 * @return Value datum
	 */
	@SuppressWarnings("unchecked")
	public <R> R getValue() {
		return (R) datumRef.getValue();
	}

	/**
	 * Gets the metadata for this syntax object. May be empty, but never null.
	 * 
	 * @return Metadata for this Syntax Object as a hashmap
	 */
	public AHashMap<ACell, ACell> getMeta() {
		return meta;
	}

	public Long getStart() {
		Object v= meta.get(Keywords.START);
		if (v instanceof CVMLong) return ((CVMLong)v).longValue();
		return null;
	}

	public Long getEnd() {
		Object v= meta.get(Keywords.END);
		if (v instanceof CVMLong) return ((CVMLong)v).longValue();
		return null;
	}

	public String getSource() {
		Object v= meta.get(Keywords.SOURCE);
		if (v instanceof AString) return v.toString();
		return null;
	}

	@Override
	public boolean isCanonical() {
		return true;
	}
	
	@Override public final boolean isCVMValue() {
		return true;
	}

	public static Syntax read(ByteBuffer bb) throws BadFormatException {
		Ref<ACell> datum = Format.readRef(bb);
		AHashMap<ACell, ACell> props = Format.read(bb);
		if (props == null) {
			props = Maps.empty(); // we encode empty props as null for efficiency
		} else {
			if (props.isEmpty()) {
				throw new BadFormatException("Empty Syntax metadata should be encoded as nil");
			}
		}
		return new Syntax(datum, props);
	}

	@Override
	public int encode(byte[] bs, int pos) {
		bs[pos++]=Tag.SYNTAX;
		return encodeRaw(bs,pos);
	}

	@Override
	public int encodeRaw(byte[] bs, int pos) {
		pos=datumRef.encode(bs,pos);
		// encode empty props as null for efficiency
		if (meta.isEmpty()) {
			bs[pos++]=Tag.NULL;
		} else {
			pos=meta.encode(bs,pos);
		}
		return pos;
	}

	@Override
	public void ednString(StringBuilder sb) {
		sb.append("#syntax {:datum ");
		Utils.ednString(sb, datumRef.getValue());
		sb.append(" :meta ");
		Utils.ednString(sb,meta);
		sb.append('}');
	}
	
	@Override
	public void print(StringBuilder sb) {
		if (meta==null) {
			sb.append("^{} ");
		} else {
			sb.append('^');
			meta.print(sb);
			sb.append(' ');
		}
		Utils.print(sb, datumRef.getValue());
	}
	
	@Override
	public String toString() {
		return print();
	}

	@Override
	public void validateCell() throws InvalidDataException {
		if (datumRef == null) throw new InvalidDataException("null datum ref", this);
		if (meta == null) throw new InvalidDataException("null metadata", this);
		meta.validateCell();
	}
	
	@Override
	public void validate() throws InvalidDataException {
		super.validate();
		if (datumRef.getValue() instanceof Syntax) {
			throw new InvalidDataException("Cannot double-wrap a Syntax value",this);
		}
	}

	@Override
	public int estimatedEncodingSize() {
		return 1+2*Format.MAX_EMBEDDED_LENGTH;
	}

	@Override
	public int getRefCount() {
		return 1 + meta.getRefCount();
	}

	@SuppressWarnings("unchecked")
	@Override
	public <R extends ACell> Ref<R> getRef(int i) {
		if (i == 0) return (Ref<R>) datumRef;
		return meta.getRef(i - 1);
	}

	
	@Override
	public Syntax updateRefs(IRefFunction func) {
		@SuppressWarnings("unchecked")
		Ref<ACell> newDatum = (Ref<ACell>)func.apply(datumRef);
		AHashMap<ACell, ACell> newMeta = meta.updateRefs(func);
		if ((datumRef == newDatum) && (meta == newMeta)) return this;
		return new Syntax(newDatum, newMeta);
	}

	/**
	 * Merges metadata into this syntax object, overriding existing metadata
	 * 
	 * @param additionalMetadata
	 * @return Syntax Object with updated metadata
	 */
	public Syntax mergeMeta(AHashMap<ACell, ACell> additionalMetadata) {
		AHashMap<ACell, ACell> mm = meta;
		mm = mm.merge(additionalMetadata);
		return this.withMeta(mm);
	}
	
	/** 
	 * Merge metadata into a Cell, after wrapping as a Syntax Object
	 * 
	 * @param original Cell to enhance with merged metadata
	 * @param additional Syntax Object containing additional metadata
	 * @return
	 */
	public static Syntax mergeMeta(ACell original, Syntax additional) {
		Syntax x=Syntax.create(original);
		if (additional!=null) {
			x=x.mergeMeta(additional.getMeta());
		}
		return x;
	}

	/**
	 * Replaces metadata on this Syntax Object. Old metadata is discarded.
	 * 
	 * @param newMetadata
	 * @return Syntax Object with updated metadata
	 */
	public Syntax withMeta(AHashMap<ACell, ACell> newMetadata) {
		if (meta == newMetadata) return this;
		return new Syntax(datumRef, newMetadata);
	}
	
	/**
	 * Updates Syntax with a new value. Always creates a new Syntax Object.
	 * @param newValue
	 * @return new Syntax Object
	 */
	public Syntax withValue(ACell newValue) {
		return create(newValue,meta);
	}

	/**
	 * Removes all metadata from this Syntax Object
	 * 
	 * @return Syntax Object with empty metadata
	 */
	public Syntax withoutMeta() {
		return withMeta(Maps.empty());
	}

	/**
	 * Unwraps a Syntax Object to get the underlying value.
	 * 
	 * If the argument is not a Syntax object, return it unchanged (already unwrapped)
	 * @param <R> Expected type of value
	 * @param x Any Object, which may be a Syntax Object
	 * @return The unwrapped value
	 */
	@SuppressWarnings("unchecked")
	public static <R> R unwrap(ACell x) {
		return (x instanceof Syntax) ? ((Syntax) x).getValue() : (R) x;
	}

	/**
	 * Recursively unwraps a Syntax object
	 * 
	 * @param maybeSyntax Syntax Object to unwrap
	 * @return Unwrapped object
	 */
	@SuppressWarnings("unchecked")
	public static <R extends ACell> R unwrapAll(ACell maybeSyntax) {
		ACell a = unwrap(maybeSyntax);

		if (a instanceof ACollection) {
			return (R) ((ACollection<?>) a).map(e -> unwrapAll(e));
		} else if (a instanceof AMap) {
			AMap<?, ?> m = (AMap<?, ?>) a;
			return (R) m.reduceEntries((acc, e) -> {
				return acc.assoc(unwrapAll(e.getKey()), unwrapAll(e.getValue()));
			}, (AMap<ACell, ACell>) Maps.empty());
		} else {
			// nothing else can contain Syntax objects, so just return normally
			return (R) a;
		}
	}

	@Override
	public byte getTag() {
		return Tag.SYNTAX;
	}

	@Override
	public ACell toCanonical() {
		return this;
	}



}
