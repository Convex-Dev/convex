package convex.core.data;

import java.nio.ByteBuffer;

import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
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
	/**
	 * Ref to the unwrapped datum value. Cannot refer to another Syntax object
	 */
	private final Ref<Object> datumRef;

	/** 
	 * Metadata map
	 * If empty, gets encoded as null in byte encoding
	 */
	private final AHashMap<Object, Object> meta;

	private Syntax(Ref<Object> datumRef, AHashMap<Object, Object> props) {
		this.datumRef = datumRef;
		this.meta = props;
	}

	/**
	 * Wraps a value as a Syntax Object, adding the given new metadata
	 * 
	 * @param value
	 * @param metadata to merge, may be null
	 * @return Syntax instance
	 */
	public static Syntax create(Object value, AHashMap<Object, Object> meta) {
		if (value instanceof Syntax) {
			Syntax stx=((Syntax) value);
			if (meta==null) return stx;
			return stx.mergeMeta(meta);
		}
		if (meta==null) meta=Maps.empty();
		
		return new Syntax(Ref.create(value), meta);
	}

	/**
	 * Wraps a value as a Syntax Object with empty metadata. Does not change existing Syntax objects.
	 * 
	 * @param value
	 * @return Syntax instance
	 */
	public static Syntax create(Object value) {
		if (value instanceof Syntax) return (Syntax) value;
		return create(value, Maps.empty());
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
	public AHashMap<Object, Object> getMeta() {
		return meta;
	}

	public Long getStart() {
		return (Long) meta.get(Keywords.START);
	}

	public Long getEnd() {
		return (Long) meta.get(Keywords.END);
	}

	public String getSource() {
		return (String) meta.get(Keywords.SOURCE);
	}

	@Override
	public boolean isCanonical() {
		return true;
	}

	public static Syntax read(ByteBuffer bb) throws BadFormatException {
		Ref<Object> datum = Format.readRef(bb);
		AHashMap<Object, Object> props = Format.read(bb);
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
	public ByteBuffer write(ByteBuffer bb) {
		bb.put(Tag.SYNTAX);
		return writeRaw(bb);
	}

	@Override
	public ByteBuffer writeRaw(ByteBuffer bb) {
		datumRef.write(bb);
		// encode empty props as null for efficiency
		if (meta.isEmpty()) {
			bb.put(Tag.NULL);
		} else {
			meta.write(bb);
		}
		return bb;
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
		Utils.print(sb, datumRef.getValue());
	}
	
	@Override
	public String toString() {
		return Utils.ednString(datumRef.getValue());
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
		return 100;
	}

	@Override
	public int getRefCount() {
		return 1 + meta.getRefCount();
	}

	@SuppressWarnings("unchecked")
	@Override
	public <R> Ref<R> getRef(int i) {
		if (i == 0) return (Ref<R>) datumRef;
		return meta.getRef(i - 1);
	}

	
	@Override
	public Syntax updateRefs(IRefFunction func) {
		@SuppressWarnings("unchecked")
		Ref<Object> newDatum = (Ref<Object>)func.apply(datumRef);
		AHashMap<Object, Object> newMeta = meta.updateRefs(func);
		if ((datumRef == newDatum) && (meta == newMeta)) return this;
		return new Syntax(newDatum, newMeta);
	}

	/**
	 * Merges metadata into this syntax object, overriding existing metadata
	 * 
	 * @param additionalMetadata
	 * @return Syntax Object with updated metadata
	 */
	public Syntax mergeMeta(AHashMap<Object, Object> additionalMetadata) {
		AHashMap<Object, Object> mm = meta;
		mm = mm.merge(additionalMetadata);
		return this.withMeta(mm);
	}

	/**
	 * Replaces metadata on this Syntax Object. Old metadata is discarded.
	 * 
	 * @param newMetadata
	 * @return Syntax Object with updated metadata
	 */
	public Syntax withMeta(AHashMap<Object, Object> newMetadata) {
		if (meta == newMetadata) return this;
		return new Syntax(datumRef, newMetadata);
	}
	
	/**
	 * Updates Syntax with a new value. Always creates a new Syntax Object.
	 * @param newValue
	 * @return new Syntax Object
	 */
	public Syntax withValue(Object newValue) {
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
	public static <R> R unwrap(Object x) {
		return (x instanceof Syntax) ? ((Syntax) x).getValue() : (R) x;
	}

	/**
	 * Recursively unwraps a Syntax object
	 * 
	 * @return Unwrapped object
	 */
	@SuppressWarnings("unchecked")
	public static <R> R unwrapAll(Object maybeSyntax) {
		Object a = unwrap(maybeSyntax);

		if (a instanceof ADataStructure) {
			if (a instanceof ACollection) {
				return (R) ((ACollection<?>) a).map(e -> unwrapAll(e));
			} else if (a instanceof AMap) {
				AMap<?, ?> m = (AMap<?, ?>) a;
				return (R) m.reduceEntries((acc, e) -> {
					return acc.assoc(unwrapAll(e.getKey()), unwrapAll(e.getValue()));
				}, (AMap<Object, Object>) Maps.empty());
			} else {
				throw new Error("Don't know how to unrap data structure of type: "+Utils.getClassName(a));
			}
		} else {
			return (R) a;
		}
	}

	@Override
	protected boolean isEmbedded() {
		// TODO  Figure out if we can be more aggressive here?
		// return Format.isEmbedded(datum) && Format.isEmbedded(meta);
		return false;
	}


}
