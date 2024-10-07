package convex.core.data;

import convex.core.data.prim.CVMLong;
import convex.core.data.type.AType;
import convex.core.data.type.Types;
import convex.core.data.util.BlobBuilder;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.lang.RT;

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
public final class Syntax extends ACell {
	public static final Syntax EMPTY = create(null, null);

	public static final AString EMPTY_META_PREFIX = Strings.create("^{} ");

	/**
	 * Max encoding size is a Map (with substituted tag) plus an embedded datum
	 */
	public static final int MAX_ENCODING_LENGTH = 1+ (Maps.MAX_ENCODING_SIZE) + Format.MAX_EMBEDDED_LENGTH; 
			
	/**
	 * Ref to the unwrapped datum value. Cannot refer to another Syntax object
	 */
	private final Ref<ACell> datumRef;

	/** 
	 * Metadata map
	 * Never null logically, but if empty, gets encoded as null in byte encoding
	 */
	private final AHashMap<ACell, ACell> meta;

	private Syntax(Ref<ACell> datumRef, AHashMap<ACell, ACell> props) {
		this.datumRef = datumRef;
		this.meta = props;
	}
	
	@Override
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
	 * @param value Value to wrap
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
	
	@Override public final boolean isDataValue() {
		return true;
	}


	/**
	 * Decodes a Syntax object from a Blob encoding
	 * 
	 * @param b Blob to read from
	 * @param pos Start position in Blob (location of tag byte)
	 * @return New decoded instance
	 * @throws BadFormatException In the event of any encoding error
	 */
	public static Syntax read(Blob b, int pos) throws BadFormatException {
		int epos=pos+1; // read position after tag
		Ref<ACell> datum = Format.readRef(b,epos);
		
		epos+=datum.getEncodingLength();
		AHashMap<ACell, ACell> props = Format.read(b,epos);
		epos+=Format.getEncodingLength(props);
		
		if (props == null) {
			props = Maps.empty(); // we encode empty props as null for efficiency
		} else {
			if (props.isEmpty()) {
				throw new BadFormatException("Empty Syntax metadata should be encoded as nil");
			}
		}
		Syntax result=new Syntax(datum,props);
		result.attachEncoding(b.slice(pos,epos));
		return result;
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
			pos=Format.write(bs,pos,meta);
		}
		return pos;
	}

	@Override
	public boolean print(BlobBuilder bb, long limit) {
		if (meta==null) {
			bb.append(EMPTY_META_PREFIX);
		} else {
			bb.append('^');
			if (!meta.print(bb,limit)) return false;
			bb.append(' ');
		}
		
		// Print rest of value according to remaining limit
		return RT.print(bb, datumRef.getValue(),limit);
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
		ACell datum=datumRef.getValue();
		if (datum!=null) {
			if (datum instanceof Syntax) {
				throw new InvalidDataException("Cannot double-wrap a Syntax value",this);
			}
			if (!datum.isCVMValue()) throw new InvalidDataException("Syntax can only wrap CVM values",this);
		}
	}

	@Override
	public int estimatedEncodingSize() {
		return 1+meta.estimatedEncodingSize()+Format.MAX_EMBEDDED_LENGTH;
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
	 * @param additionalMetadata Extra metadata to merge
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
	 * @param additional Syntax Object containing additional metadata. Any value will be ignored.
	 * @return Syntax object with merged metadata
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
	 * @param newMetadata New metadata map
	 * @return Syntax Object with updated metadata
	 */
	public Syntax withMeta(AHashMap<ACell, ACell> newMetadata) {
		if (meta == newMetadata) return this;
		return new Syntax(datumRef, newMetadata);
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

	@Override
	public boolean equals(ACell o) {
		if (!(o instanceof Syntax)) return false; // catches null
		Syntax b=(Syntax)o;
		if (!meta.equals(b.meta)) return false;
		return datumRef.equals(b.datumRef);
	}




}
