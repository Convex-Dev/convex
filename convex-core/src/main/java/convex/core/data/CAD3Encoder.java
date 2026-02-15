package convex.core.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

import convex.core.Constants;
import convex.core.cvm.Address;
import convex.core.cvm.CVMTag;
import convex.core.cvm.Syntax;
import convex.core.crypto.ASignature;
import convex.core.crypto.Ed25519Signature;
import convex.core.data.prim.AByteFlag;
import convex.core.data.prim.ANumeric;
import convex.core.data.prim.CVMBigInteger;
import convex.core.data.prim.CVMChar;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.MissingDataException;
import convex.core.store.AStore;
import convex.core.store.Stores;
import convex.core.util.ErrorMessages;
import convex.core.util.Trees;
import convex.core.util.Utils;

/**
 * Encoder for standard CAD3 data types.
 *
 * Handles all base CAD3 types: numerics, strings, blobs, data structures,
 * signed data, byte flags, and generic coded/dense/extension values.
 *
 * Does NOT decode CVM-specific types (transactions, ops, consensus records).
 * Use the derived CVMEncoder for CVM type support.
 *
 * Subclasses override the category read methods (readCodedData, readDenseRecord,
 * readExtension) to add domain-specific types, falling back to generic CAD3
 * representations (CodedValue, DenseRecord, ExtensionValue) for unrecognised tags.
 */
public class CAD3Encoder extends AEncoder<ACell> {

	public static final CAD3Encoder INSTANCE = new CAD3Encoder();

	/**
	 * Store associated with this encoder. When non-null, the encoder manages
	 * the thread-local store context during decode operations so that
	 * RefSoft.createForHash captures the correct store.
	 */
	protected final AStore store;

	public CAD3Encoder() {
		this.store = null;
	}

	public CAD3Encoder(AStore store) {
		this.store = store;
	}

	@Override
	public Blob encode(ACell a) {
		return Cells.encode(a);
	}

	/**
	 * Decodes a single cell from a complete Blob encoding.
	 * Uses the encoder's bound store if available, otherwise falls back to
	 * the thread-local store. Ensures the thread-local is set correctly for
	 * RefSoft creation during the decode.
	 */
	@Override
	public ACell decode(Blob encoding) throws BadFormatException {
		if (encoding.count()<1) throw new BadFormatException("Empty encoding");
		AStore effectiveStore = (this.store != null) ? this.store : Stores.current();
		if (effectiveStore != null) {
			AStore saved = Stores.current();
			if (saved == effectiveStore) return read(encoding, 0);
			Stores.setCurrent(effectiveStore);
			try {
				return read(encoding, 0);
			} finally {
				Stores.setCurrent(saved);
			}
		}
		return read(encoding, 0);
	}

	@Override
	public ACell read(byte tag, Blob encoding, int offset) throws BadFormatException {
		try {
			// Mask to unsigned before shifting: Java byte is signed, so tags >= 0x80
			// give negative values with plain >> which won't match cases 8-15
			switch ((tag & 0xFF)>>4) {
			case 0: // 0x00-0x0F
				if (tag==Tag.NULL) return null;
				break;

			case 1: // 0x10-0x1F : Numeric values
				if (tag==0x10) return CVMLong.ZERO; // fast path
				return readNumeric(tag,encoding,offset);

			case 2: // 0x20-0x2F : Addresses and references
				if (tag == CVMTag.ADDRESS) return Address.read(encoding,offset);
				break;

			case 3: // 0x30-0x3F : Basic string / blob-like objects
				return readBasicObject(tag, encoding, offset);

			case 4: case 5: case 6: case 7: // 0x40-0x7F currently reserved
				break;

			case 8: // 0x80-0x8F : Data structures
				return readDataStructure(tag, encoding, offset);

			case 9: // 0x90-0x9F : Signed data
				return readSignedData(tag,encoding, offset);

			case 10: // 0xA0-0xAF : Sparse records
				return readSparseRecord(tag,encoding,offset);

			case 11: // 0xB0-0xBF : Byte flags including booleans
				return AByteFlag.read(tag);

			case 12: // 0xC0-0xCF : Coded data objects
				return readCodedData(tag,encoding, offset);

			case 13: // 0xD0-0xDF : Dense records
				return readDenseRecord(tag,encoding, offset);

			case 14: // 0xE0-0xEF : Extension values
				return readExtension(tag,encoding, offset);

			case 15: // 0xF0-0xFF : Reserved
				break;
			}
		} catch (BadFormatException e) {
			throw e;
		} catch (IndexOutOfBoundsException e) {
			// Wrap as BadFormatException: truncated or invalid encoding
			throw new BadFormatException("Read out of bounds when decoding with tag 0x"+Utils.toHexString(tag),e);
		} catch (MissingDataException e) {
			throw e;
		} catch (Exception e) {
			throw new BadFormatException("Unexpected Exception when decoding ("+tag+"): "+e.getMessage(), e);
		}

		throw new BadFormatException(ErrorMessages.badTagMessage(tag));
	}

	protected ANumeric readNumeric(byte tag, Blob blob, int offset) throws BadFormatException {
		if (tag<0x19) return CVMLong.read(tag,blob,offset);
		if (tag == 0x19) return CVMBigInteger.read(blob,offset);
		if (tag == Tag.DOUBLE) return CVMDouble.read(tag,blob,offset);
		throw new BadFormatException(ErrorMessages.badTagMessage(tag));
	}

	protected ACell readBasicObject(byte tag, Blob blob, int offset) throws BadFormatException {
		switch (tag) {
			case Tag.SYMBOL: return Symbol.read(blob,offset);
			case Tag.KEYWORD: return Keyword.read(blob,offset);
			case Tag.BLOB: return Blobs.read(blob,offset);
			case Tag.STRING: return Strings.read(blob,offset);
		}
		if ((tag&Tag.CHAR_MASK)==Tag.CHAR_BASE) {
			int len=CVMChar.byteCountFromTag(tag);
			if (len>4) throw new BadFormatException("Can't read char type with length: " + len);
			return CVMChar.read(len, blob,offset);
		}
		throw new BadFormatException(ErrorMessages.badTagMessage(tag));
	}

	protected ACell readDataStructure(byte tag, Blob b, int pos) throws BadFormatException {
		if (tag == Tag.VECTOR) return Vectors.read(b,pos);
		if (tag == Tag.MAP) return Maps.read(b,pos);
		if (tag == CVMTag.SYNTAX) return Syntax.read(b,pos);
		if (tag == Tag.SET) return Sets.read(b,pos);
		if (tag == Tag.LIST) return List.read(b,pos);
		if (tag == Tag.INDEX) return Index.read(b,pos);
		throw new BadFormatException("Can't read data structure with tag byte: " + tag);
	}

	protected SignedData<?> readSignedData(byte tag, Blob blob, int offset) throws BadFormatException {
		if (tag==Tag.SIGNED_DATA) return SignedData.read(blob,offset,true);
		if (tag==Tag.SIGNED_DATA_SHORT) return SignedData.read(blob,offset,false);
		throw new BadFormatException(ErrorMessages.badTagMessage(tag));
	}

	protected ARecord<?,?> readSparseRecord(byte tag, Blob encoding, int offset) throws BadFormatException {
		throw new BadFormatException(ErrorMessages.TODO);
	}

	/**
	 * Reads a coded data value. Override in subclasses to handle specific coded types
	 * (e.g. CVM ops), falling back to generic CodedValue for unknown tags.
	 */
	protected ACell readCodedData(byte tag, Blob encoding, int offset) throws BadFormatException {
		return CodedValue.read(tag,encoding,offset);
	}

	/**
	 * Reads a dense record. Override in subclasses to handle specific dense record types
	 * (e.g. CVM transactions, consensus records), falling back to generic DenseRecord.
	 */
	protected ACell readDenseRecord(byte tag, Blob encoding, int offset) throws BadFormatException {
		DenseRecord dr=DenseRecord.read(tag,encoding,offset);
		if (dr==null) throw new BadFormatException(ErrorMessages.badTagMessage(tag));
		return dr;
	}

	/**
	 * Reads an extension value. Override in subclasses to handle specific extension types
	 * (e.g. CVM core defs, special ops), falling back to generic ExtensionValue.
	 */
	protected ACell readExtension(byte tag, Blob blob, int offset) throws BadFormatException {
		long code=Format.readVLQCount(blob,offset+1);
		return ExtensionValue.create(tag, code);
	}

	// ==================== DecodeState-based operations ====================

	/**
	 * Reads a Ref or embedded cell from the decode state, advancing pos.
	 * For non-embedded refs, creates RefSoft bound to this encoder's store
	 * (no thread-local lookup). For embedded cells, dispatches through this
	 * encoder's virtual read for correct type resolution.
	 *
	 * @param <T> Type of referenced value
	 * @param ds Decode state to read from
	 * @return Ref to the decoded value
	 * @throws BadFormatException If encoding is invalid
	 */
	@SuppressWarnings("unchecked")
	public <T extends ACell> Ref<T> readRef(DecodeState ds) throws BadFormatException {
		byte tag = ds.data[ds.pos];
		if (tag==Tag.REF) {
			ds.pos++;
			Hash h = Hash.wrap(ds.data, ds.pos);
			if (h==null) throw new BadFormatException("Insufficient bytes to read Ref at position: "+ds.pos);
			ds.pos += Hash.LENGTH;
			Ref<T> ref = Ref.forHash(h, store);
			return ref.markEmbedded(false);
		}
		if (tag==Tag.NULL) {
			ds.pos++;
			return Ref.nil();
		}
		// Embedded cell — dispatch through this encoder's virtual read
		T cell = (T) this.read(ds);
		if (!cell.isEmbedded()) throw new BadFormatException("Non-embedded cell found instead of ref");
		return cell.getRef();
	}

	/**
	 * Reads a VLQ-encoded count from the decode state, advancing pos.
	 *
	 * @param ds Decode state to read from
	 * @return Decoded count value
	 * @throws BadFormatException If VLQ encoding is invalid
	 */
	public long readVLQCount(DecodeState ds) throws BadFormatException {
		long count = Format.readVLQCount(ds.data, ds.pos);
		ds.pos += Format.getVLQCountLength(count);
		return count;
	}

	/**
	 * Reads a cell from the decode state by extracting tag and dispatching.
	 * Advances ds.pos past the full encoding.
	 *
	 * @param ds Decode state to read from
	 * @return Decoded cell (may be null for Tag.NULL)
	 * @throws BadFormatException If encoding is invalid
	 */
	@Override
	public ACell read(DecodeState ds) throws BadFormatException {
		int startPos = ds.pos;
		byte tag = ds.readByte();
		try {
			switch ((tag & 0xFF)>>4) {
			case 0:
				if (tag==Tag.NULL) return null;
				break;

			case 1: // Numerics
				return readNumeric(tag, ds);

			case 3: // 0x30-0x3F : Strings, blobs, symbols, keywords, chars
				return readBasicObject(tag, ds);

			case 8: // Data structures (Syntax excluded — needs ref-taking constructor, Phase 3)
				if (tag != CVMTag.SYNTAX) return readDataStructure(tag, ds);
				break;

			case 9: // 0x90-0x9F : Signed data
				return readSignedData(tag, ds);

			case 11: // 0xB0-0xBF : Byte flags including booleans
				return AByteFlag.read(tag);

			case 14: // 0xE0-0xEF : Extension values
				return readExtension(tag, ds);
			}
		} catch (BadFormatException e) {
			throw e;
		} catch (IndexOutOfBoundsException e) {
			throw new BadFormatException("Read out of bounds when decoding with tag 0x"+Utils.toHexString(tag),e);
		} catch (MissingDataException e) {
			throw e;
		} catch (Exception e) {
			throw new BadFormatException("Unexpected Exception when decoding ("+tag+"): "+e.getMessage(), e);
		}

		// Fall through: bridge to Blob-based read for unmigrated types
		// TODO: remove bridge once all types use native DecodeState reads
		Blob b = Blob.wrap(ds.data, startPos, ds.limit - startPos);
		ACell result = read(tag, b, 0);
		if (result != null) {
			ds.advanceTo(startPos + result.getEncodingLength());
		}
		return result;
	}

	// ---- DecodeState type reads (private) ----

	private ANumeric readNumeric(byte tag, DecodeState ds) throws BadFormatException {
		// CVMLong: tag 0x10-0x18, tag encodes byte count
		if (tag<0x19) {
			int numBytes=tag-Tag.INTEGER;
			if (numBytes==0) return CVMLong.ZERO;
			long v=Format.readLong(ds.data, ds.pos, numBytes);
			ds.pos+=numBytes;
			return CVMLong.create(v);
		}
		// CVMBigInteger: tag 0x19, VLQ byte count + raw bytes
		if (tag == 0x19) {
			long bc=Format.readVLQCount(ds.data, ds.pos);
			ds.pos+=Format.getVLQCountLength(bc);
			if (bc<=8) throw new BadFormatException("Non-canonical big integer length");
			if (bc>Constants.MAX_BIG_INTEGER_LENGTH) throw new BadFormatException("Encoding exceeds max big integer length");
			Blob blobData=Blob.wrap(ds.data, ds.pos, (int)bc);
			ds.pos+=(int)bc;
			CVMBigInteger result=CVMBigInteger.create(blobData);
			if (result==null) throw new BadFormatException("Illegal creation of BigInteger from blob");
			if (result.byteLength()!=bc) throw new BadFormatException("Excess leading bytes in BigInteger representation");
			return result;
		}
		// CVMDouble: tag 0x1D, 8 raw bytes (IEEE 754)
		if (tag == Tag.DOUBLE) {
			long bits=Utils.readLong(ds.data, ds.pos, 8);
			ds.pos+=8;
			return CVMDouble.unsafeCreate(Double.longBitsToDouble(bits));
		}
		throw new BadFormatException(ErrorMessages.badTagMessage(tag));
	}

	@SuppressWarnings("unchecked")
	private <T extends ACell> AVector<T> readVector(DecodeState ds) throws BadFormatException {
		long count = readVLQCount(ds);
		if (count < 0) throw new BadFormatException("Negative length");

		if (VectorLeaf.isValidCount(count)) {
			// VectorLeaf: 0-16 tail items with optional packed prefix
			if (count == 0) return (AVector<T>) VectorLeaf.EMPTY;
			int n = ((int) count) & 0xF;
			if (n == 0) {
				if (count > 16) throw new BadFormatException("Vector not valid for size 0 mod 16: " + count);
				n = VectorLeaf.MAX_SIZE;
			}
			Ref<T>[] items = (Ref<T>[]) new Ref<?>[n];
			for (int i = 0; i < n; i++) {
				items[i] = readRef(ds);
			}
			Ref<AVector<T>> pfx = null;
			if (count > VectorLeaf.MAX_SIZE) {
				pfx = readRef(ds);
			}
			return new VectorLeaf<T>(items, pfx, count);
		} else {
			// VectorTree: merkle tree of child vector refs
			int n = VectorTree.computeArraySize(count);
			Ref<AVector<T>>[] items = (Ref<AVector<T>>[]) new Ref<?>[n];
			for (int i = 0; i < n; i++) {
				items[i] = readRef(ds);
			}
			return new VectorTree<T>(items, count);
		}
	}

	private ACell readBasicObject(byte tag, DecodeState ds) throws BadFormatException {
		if (tag == Tag.BLOB) {
			// Blob: VLQ count + flat data or child refs (BlobTree)
			long count = readVLQCount(ds);
			if (count < 0) throw new BadFormatException("Negative blob length");
			if (count <= Blob.CHUNK_LENGTH) {
				if (count == 0) return Blob.EMPTY;
				Blob result = Blob.wrap(ds.data, ds.pos, (int) count);
				ds.pos += (int) count;
				return result;
			}
			return readBlobTree(count, ds);
		}
		if (tag == Tag.STRING) {
			// String: VLQ length + flat UTF-8 data or child refs (StringTree wrapping BlobTree)
			long length = readVLQCount(ds);
			if (length < 0) throw new BadFormatException("Negative string length");
			if (length > Integer.MAX_VALUE) throw new BadFormatException("String length too long: " + length);
			if (length <= StringShort.MAX_LENGTH) {
				Blob data = Blob.wrap(ds.data, ds.pos, (int) length);
				ds.pos += (int) length;
				return StringShort.create(data);
			}
			// StringTree wraps a BlobTree with same structure
			BlobTree bt = readBlobTree(length, ds);
			return StringTree.create(bt);
		}
		if (tag == Tag.SYMBOL) {
			// Symbol: 1-byte length + UTF-8 name bytes
			int len = 0xff & ds.data[ds.pos++];
			AString name = Strings.create(Blob.wrap(ds.data, ds.pos, len));
			ds.pos += len;
			Symbol sym = Symbol.create(name);
			if (sym == null) throw new BadFormatException("Can't read symbol");
			return sym;
		}
		if (tag == Tag.KEYWORD) {
			// Keyword: 1-byte length + UTF-8 name bytes
			int len = 0xff & ds.data[ds.pos++];
			if (len > Keyword.MAX_CHARS) throw new BadFormatException("Keyword too long");
			AString name = Strings.create(Blob.wrap(ds.data, ds.pos, len));
			ds.pos += len;
			Keyword kw = Keyword.create(name);
			if (kw == null) throw new BadFormatException("Can't read keyword");
			return kw;
		}
		// CVMChar: tag 0x3c-0x3f, low 2 bits encode length-1
		if ((tag & Tag.CHAR_MASK) == Tag.CHAR_BASE) {
			int len = (tag & 0x03) + 1;
			if (len > 4) throw new BadFormatException("Can't read char type with length: " + len);
			int value = 0xff000000; // sentinel high byte, shifted away
			for (int i = 0; i < len; i++) {
				if (value == 0) throw new BadFormatException("Leading zero in CVMChar encoding");
				value = (value << 8) + (0xff & ds.data[ds.pos++]);
			}
			CVMChar result = CVMChar.create(value);
			if (result == null) throw new BadFormatException("CVMChar out of Unicode range");
			return result;
		}
		throw new BadFormatException(ErrorMessages.badTagMessage(tag));
	}

	@SuppressWarnings("unchecked")
	private BlobTree readBlobTree(long count, DecodeState ds) throws BadFormatException {
		// BlobTree: child refs to blob chunks
		long chunks = BlobTree.calcChunks(count);
		int shift = BlobTree.calcShift(chunks);
		int numChildren = Utils.checkedInt(((chunks - 1) >> shift) + 1);
		Ref<ABlob>[] children = (Ref<ABlob>[]) new Ref<?>[numChildren];
		for (int i = 0; i < numChildren; i++) {
			Ref<ABlob> ref = readRef(ds);
			if (ref == Ref.NULL_VALUE) throw new BadFormatException("Null BlobTree child");
			children[i] = ref;
		}
		return new BlobTree(children, shift, count);
	}

	// ---- Protected category methods for DecodeState (overridable by CVMEncoder) ----

	protected ACell readDataStructure(byte tag, DecodeState ds) throws BadFormatException {
		if (tag == Tag.VECTOR) return readVector(ds);
		if (tag == Tag.MAP) return readMap(ds);
		if (tag == Tag.SET) return readSet(ds);
		if (tag == Tag.LIST) return readList(ds);
		if (tag == Tag.INDEX) return readIndex(ds);
		throw new BadFormatException("Can't read data structure with tag byte: " + tag);
	}

	protected SignedData<?> readSignedData(byte tag, DecodeState ds) throws BadFormatException {
		if (tag == Tag.SIGNED_DATA) {
			// Full signed data: 32-byte public key + 64-byte signature + value ref
			AccountKey pubKey = AccountKey.wrap(ds.data, ds.pos);
			ds.pos += AccountKey.LENGTH;
			ASignature sig = Ed25519Signature.wrap(ds.data, ds.pos);
			ds.pos += Ed25519Signature.SIGNATURE_LENGTH;
			Ref<ACell> value = readRef(ds);
			return SignedData.create(pubKey, sig, value);
		}
		if (tag == Tag.SIGNED_DATA_SHORT) {
			// Short signed data: 64-byte signature + value ref (no key)
			ASignature sig = Ed25519Signature.wrap(ds.data, ds.pos);
			ds.pos += Ed25519Signature.SIGNATURE_LENGTH;
			Ref<ACell> value = readRef(ds);
			return SignedData.create(null, sig, value);
		}
		throw new BadFormatException(ErrorMessages.badTagMessage(tag));
	}

	protected ACell readCodedData(byte tag, DecodeState ds) throws BadFormatException {
		// Generic coded value: code ref + value ref
		Ref<ACell> cref = readRef(ds);
		Ref<ACell> vref = readRef(ds);
		return new CodedValue(tag, cref, vref);
	}

	protected ACell readDenseRecord(byte tag, DecodeState ds) throws BadFormatException {
		// Dense record: reads as vector data, wraps with tag
		AVector<ACell> data = readVector(ds);
		DenseRecord dr = DenseRecord.create(tag & 0xFF, data);
		if (dr == null) throw new BadFormatException(ErrorMessages.badTagMessage(tag));
		return dr;
	}

	protected ACell readExtension(byte tag, DecodeState ds) throws BadFormatException {
		long code = readVLQCount(ds);
		return ExtensionValue.create(tag, code);
	}

	// ---- Private data structure read helpers ----

	@SuppressWarnings("unchecked")
	private <K extends ACell, V extends ACell> AHashMap<K, V> readMap(DecodeState ds) throws BadFormatException {
		long count = readVLQCount(ds);
		if (count == 0) return Maps.empty();
		if (count < 0) throw new BadFormatException("Overflowed count of map elements!");

		if (count <= MapLeaf.MAX_ENTRIES) {
			// MapLeaf: count key-value ref pairs, sorted by key hash
			MapEntry<K, V>[] items = (MapEntry<K, V>[]) new MapEntry[(int) count];
			for (int i = 0; i < count; i++) {
				Ref<K> kr = readRef(ds);
				Ref<V> vr = readRef(ds);
				items[i] = MapEntry.fromRefs(kr, vr);
			}
			if (!MapLeaf.isValidOrder(items)) {
				throw new BadFormatException("Bad ordering of keys!");
			}
			return new MapLeaf<>(items);
		} else {
			// MapTree: shift byte + mask short + child refs
			int shift = ds.data[ds.pos++];
			short mask = (short) ((ds.data[ds.pos] << 8) | (ds.data[ds.pos + 1] & 0xFF));
			ds.pos += 2;
			int ilength = Integer.bitCount(mask & 0xFFFF);
			Ref<AHashMap<K, V>>[] blocks = (Ref<AHashMap<K, V>>[]) new Ref<?>[ilength];
			for (int i = 0; i < ilength; i++) {
				blocks[i] = readRef(ds);
			}
			MapTree<K, V> result = new MapTree<>(blocks, shift, mask, count);
			if (!result.isValidStructure()) throw new BadFormatException("Problem with TreeMap invariants");
			return result;
		}
	}

	@SuppressWarnings("unchecked")
	private <V extends ACell> ASet<V> readSet(DecodeState ds) throws BadFormatException {
		long count = readVLQCount(ds);

		if (count <= SetLeaf.MAX_ELEMENTS) {
			// SetLeaf: count element refs, sorted by hash
			if (count == 0) return Sets.empty();
			if (count < 0) throw new BadFormatException("Negative count of set elements!");
			Ref<V>[] items = (Ref<V>[]) new Ref[(int) count];
			for (int i = 0; i < count; i++) {
				items[i] = readRef(ds);
			}
			if (!SetLeaf.isValidOrder(items)) {
				throw new BadFormatException("Set elements out of order in encoding");
			}
			return new SetLeaf<V>(items);
		} else {
			// SetTree: shift byte + mask short + child refs
			int shift = ds.data[ds.pos++];
			short mask = (short) ((ds.data[ds.pos] << 8) | (ds.data[ds.pos + 1] & 0xFF));
			ds.pos += 2;
			int ilength = Integer.bitCount(mask & 0xFFFF);
			@SuppressWarnings("rawtypes")
			Ref<AHashSet<V>>[] blocks = (Ref<AHashSet<V>>[]) new Ref[ilength];
			for (int i = 0; i < ilength; i++) {
				blocks[i] = readRef(ds);
			}
			SetTree<V> result = new SetTree<>(blocks, shift, mask, count);
			if (!result.isValidStructure()) throw new BadFormatException("Problem with SetTree invariants");
			return result;
		}
	}

	@SuppressWarnings("unchecked")
	private <T extends ACell> List<T> readList(DecodeState ds) throws BadFormatException {
		// List encoding matches Vector (VLQ count + refs), wrapped in List
		AVector<T> data = readVector(ds);
		if (data.isEmpty()) return (List<T>) List.EMPTY;
		return List.wrap(data);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private <K extends ABlobLike<?>, V extends ACell> Index<K, V> readIndex(DecodeState ds) throws BadFormatException {
		long count = readVLQCount(ds);
		if (count < 0) throw new BadFormatException("Negative count!");
		if (count == 0) return (Index<K, V>) Index.EMPTY;

		// Check for entry presence
		MapEntry<K, V> me;
		boolean hasEntry;
		if (count == 1) {
			hasEntry = true;
		} else {
			byte c = ds.readByte();
			switch (c) {
				case Tag.NULL: hasEntry = false; break;
				case Tag.VECTOR: hasEntry = true; break;
				default: throw new BadFormatException("Invalid MapEntry tag in Index: " + c);
			}
		}

		if (hasEntry) {
			Ref<K> kr = readRef(ds);
			Ref<V> vr = readRef(ds);
			me = MapEntry.fromRefs(kr, vr);

			if (count == 1) {
				// Single entry — depth derived from key
				long depth = kr.isEmbedded() ? kr.getValue().hexLength() : 64;
				return new Index<K, V>(depth, me, Index.EMPTY_CHILDREN, (short) 0, 1L);
			}
		} else {
			me = null;
		}

		// Read depth byte, mask, and children
		int depth = 0xFF & ds.data[ds.pos++];
		if (depth >= 64) {
			if (depth == 64) throw new BadFormatException("More than one entry and MAX_DEPTH");
			throw new BadFormatException("Excessive depth!");
		}

		short mask = (short) ((ds.data[ds.pos] << 8) | (ds.data[ds.pos + 1] & 0xFF));
		ds.pos += 2;
		int n = Integer.bitCount(mask & 0xFFFF);
		Ref<Index>[] children = new Ref[n];
		for (int i = 0; i < n; i++) {
			children[i] = readRef(ds);
		}

		return new Index<K, V>(depth, me, children, mask, count);
	}

	// ==================== Multi-cell decode ====================

	/**
	 * Decodes a cell from multi-cell encoded data. The top-level cell is
	 * followed by VLQ-prefixed child cells. All reads dispatch through this
	 * encoder's virtual {@link #read} methods, so CAD3Encoder produces generic
	 * types and CVMEncoder produces CVM-specific types.
	 *
	 * PERFORMANCE: This is a hot path for all incoming messages on network. Must be optimal, minimal GC
	 * SECURITY: Input may come from untrusted sources, so must be robust to adversarial input (reject in at most O(n) time)
	 *
	 * If this encoder has an associated store, sets the thread-local store
	 * for the duration of the decode. Otherwise installs a temporary
	 * MessageStore for child cell resolution.
	 *
	 * @param data Multi-cell encoded data
	 * @return Decoded top-level cell
	 * @throws BadFormatException If encoding format is invalid
	 */
	@Override
	public ACell decodeMultiCell(Blob data) throws BadFormatException {
		int ml=Utils.checkedInt(data.count());
		if (ml<1) throw new BadFormatException("Attempt to decode from empty Blob");

		// Use encoder's bound store, falling back to thread-local store
		AStore effectiveStore = (this.store != null) ? this.store : Stores.current();
		if (effectiveStore != null) {
			return decodeMultiCellWithStore(effectiveStore, data, ml);
		}

		// No store available: install a temporary MessageStore so that
		// RefSoft.createForHash can capture it during top cell decode
		HashMap<Hash,ACell> hm=new HashMap<>();
		Stores.setCurrent(new MessageStore(hm, null));
		try {
			ACell result= this.read(data,0);
			if (result==null) {
				if (ml!=1) throw new BadFormatException("Extra bytes after nil message");
				return null;
			}
			int rl=Utils.checkedInt(result.getEncodingLength());
			if (rl==ml) return result; // single cell, done
			return resolveChildren(result, hm, data, rl, ml);
		} finally {
			Stores.setCurrent(null);
		}
	}

	/**
	 * Multi-cell decode with a known store.
	 * Sets the thread-local store for RefSoft creation during reads.
	 */
	private ACell decodeMultiCellWithStore(AStore effectiveStore, Blob data, int ml) throws BadFormatException {
		AStore saved = Stores.current();
		boolean needsRestore = (saved != effectiveStore);
		if (needsRestore) Stores.setCurrent(effectiveStore);
		try {
			ACell result= this.read(data,0);
			if (result==null) {
				if (ml!=1) throw new BadFormatException("Extra bytes after nil message");
				return null;
			}
			int rl=Utils.checkedInt(result.getEncodingLength());
			if (rl==ml) return result; // single cell, done
			return resolveChildren(result, data, rl, ml);
		} finally {
			if (needsRestore) Stores.setCurrent(saved);
		}
	}

	/**
	 * Resolves child cells from multi-cell encoded data when a store is set.
	 * Decodes children and uses a replacement scan to wire up refs.
	 */
	private ACell resolveChildren(ACell result, Blob data, int rl, int ml) throws BadFormatException {
		HashMap<Hash,ACell> hm=new HashMap<>();
		readChildCells(hm, data, rl, ml);
		return resolveRefs(result, hm);
	}

	/**
	 * Resolves child cells from multi-cell encoded data when using a MessageStore.
	 * Children are decoded into the same HashMap backing the MessageStore.
	 */
	private ACell resolveChildren(ACell result, HashMap<Hash,ACell> hm, Blob data, int rl, int ml) throws BadFormatException {
		readChildCells(hm, data, rl, ml);
		return resolveRefs(result, hm);
	}

	/**
	 * Replacement scan: resolve all non-embedded refs using decoded child cells.
	 */
	private ACell resolveRefs(ACell result, final HashMap<Hash,ACell> childMap) {
		HashMap<Hash,ACell> done=new HashMap<Hash,ACell>();
		ArrayList<ACell> stack=new ArrayList<>();

		IRefFunction func=new IRefFunction() {
			@SuppressWarnings("rawtypes")
			@Override
			public Ref apply(Ref r) {
				if (r.isEmbedded()) {
					ACell cc=r.getValue();
					if (cc==null) return r;
					ACell nc=cc.updateRefs(this);
					if (cc==nc) return r;
					return nc.getRef();
				} else {
					Hash h=r.getHash();

					// if done, just replace with done version
					ACell doneVal=done.get(h);
					if (doneVal!=null) return doneVal.getRef();

					// if in map, push cell to stack
					ACell part=childMap.get(h);
					if (part!=null) {
						stack.add(part);
						return part.getRef();
					}

					// not in message, must be partial
					return r;
				}
			}
		};

		stack.add(result);
		Trees.visitStackMaybePopping(stack, new Predicate<ACell>() {
			@Override
			public boolean test(ACell c) {
				Hash h=c.getHash();
				if (done.containsKey(h)) return true;

				int pos=stack.size();
				// Update Refs, adding new non-embedded cells to stack
				ACell nc=c.updateRefs(func);

				if (stack.size()==pos) {
					// we must be done since nothing new added to stack
					done.put(h,nc);
					return true;
				} else {
					// something extra on the stack to handle first
					stack.set(pos-1,nc);
					return false;
				}
			}
		});

		return done.get(result.getHash());
	}

	/**
	 * Decodes VLQ-prefixed non-embedded child cells into an accumulator HashMap.
	 * Dispatches through this encoder's virtual {@link #read} methods.
	 *
	 * @param acc Accumulator for cells, keyed by content hash
	 * @param data Blob containing the encoded cells
	 * @param offset Start offset in data
	 * @param end End offset in data
	 * @throws BadFormatException If encoding is invalid or contains embedded cells
	 */
	private void readChildCells(HashMap<Hash,ACell> acc, Blob data, int offset, int end) throws BadFormatException {
		try {
			int ix=offset;
			while(ix<end) {
				long encLength=Format.readVLQCount(data,ix);
				ix+=Format.getVLQCountLength(encLength);

				int childEnd=ix+(int)encLength;
				if (childEnd>end) throw new BadFormatException("Child encoding exceeds message bounds");
				Blob enc=data.slice(ix, childEnd);
				if (enc==null) throw new BadFormatException("Invalid child encoding slice");
				Hash h=enc.getContentHash();
				ACell c=this.read(enc, 0);

				if (c==null) throw new BadFormatException("Null child encoding");
				if (c.isEmbedded()) throw new BadFormatException("Embedded Cell as child");

				acc.put(h, c);
				ix+=(int)encLength;
			}
			if (ix!=end) throw new BadFormatException("Bad message length when decoding");
		} catch (IndexOutOfBoundsException e) {
			throw new BadFormatException("Insufficient bytes to decode Cells");
		}
	}

	/**
	 * Decodes VLQ-prefixed non-embedded child cells using DecodeState.
	 * Dispatches through this encoder's virtual {@link #read} methods.
	 * Reads from ds.pos to ds.limit.
	 *
	 * @param acc Accumulator for cells, keyed by content hash
	 * @param ds Decode state positioned at start of child cells
	 * @throws BadFormatException If encoding is invalid or contains embedded cells
	 */
	@SuppressWarnings("unused")
	private void readChildCells(HashMap<Hash,ACell> acc, DecodeState ds) throws BadFormatException {
		try {
			while(ds.pos < ds.limit) {
				long encLength = readVLQCount(ds);

				int childStart = ds.pos;
				int childEnd = childStart + (int)encLength;
				if (childEnd > ds.limit) throw new BadFormatException("Child encoding exceeds message bounds");

				// Compute hash from raw bytes before reading
				Hash h = Blob.wrap(ds.data, childStart, (int)encLength).getContentHash();

				ACell c = this.read(ds);

				if (c==null) throw new BadFormatException("Null child encoding");
				if (c.isEmbedded()) throw new BadFormatException("Embedded Cell as child");
				if (ds.pos != childEnd) throw new BadFormatException("Child encoding length mismatch");

				acc.put(h, c);
			}
		} catch (IndexOutOfBoundsException e) {
			throw new BadFormatException("Insufficient bytes to decode Cells");
		}
	}

	/**
	 * Temporary store used during multi-cell message decoding when no store
	 * is set on the current thread. Resolves Refs from the message's decoded
	 * child cells first, then delegates to any backing store for refs not
	 * in the message (partial messages).
	 */
	private static class MessageStore extends AStore {
		private final HashMap<Hash, ACell> cells;
		private final AStore delegate;

		MessageStore(HashMap<Hash, ACell> cells, AStore delegate) {
			this.cells = cells;
			this.delegate = delegate;
		}

		@SuppressWarnings("unchecked")
		@Override
		public <T extends ACell> Ref<T> refForHash(Hash hash) {
			ACell cell = cells.get(hash);
			if (cell != null) return (Ref<T>) cell.getRef();
			if (delegate != null) return delegate.refForHash(hash);
			return null;
		}

		@Override
		public <T extends ACell> Ref<T> storeRef(Ref<T> ref, int status, Consumer<Ref<ACell>> handler) {
			if (delegate != null) try { return delegate.storeRef(ref, status, handler); } catch (Exception e) {}
			return ref;
		}

		@Override
		public <T extends ACell> Ref<T> storeTopRef(Ref<T> ref, int status, Consumer<Ref<ACell>> handler) {
			if (delegate != null) try { return delegate.storeTopRef(ref, status, handler); } catch (Exception e) {}
			return ref;
		}

		@Override public Hash getRootHash() { return null; }
		@Override public <T extends ACell> Ref<T> setRootData(T data) { return Ref.get(data); }
		@Override public void close() {}

		@Override
		public <T extends ACell> T decode(Blob encoding) throws BadFormatException {
			if (delegate != null) return delegate.decode(encoding);
			return Format.read(encoding);
		}

		@Override
		public AEncoder<ACell> getEncoder() {
			return (delegate != null) ? delegate.getEncoder() : null;
		}

		@SuppressWarnings("unchecked")
		@Override
		public <T extends ACell> Ref<T> checkCache(Hash h) {
			ACell cell = cells.get(h);
			if (cell != null) return (Ref<T>) cell.getRef();
			if (delegate != null) return delegate.checkCache(h);
			return null;
		}

		@Override
		public String shortName() { return "message"; }
	}
}
