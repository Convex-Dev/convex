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
import convex.core.exceptions.PartialMessageException;
import convex.core.store.AStore;
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

	/**
	 * Creates a new encoder of the same type with a different store.
	 * Subclasses should override to preserve their type.
	 */
	protected CAD3Encoder withStore(AStore store) {
		return new CAD3Encoder(store);
	}

	@Override
	public Blob encode(ACell a) {
		return Cells.encode(a);
	}

	/**
	 * Decodes a single cell from a complete Blob encoding.
	 * Uses DecodeState path with this encoder's store for ref resolution.
	 */
	@Override
	public ACell decode(Blob encoding) throws BadFormatException {
		long n = encoding.count();
		if (n < 1) throw new BadFormatException("Empty encoding");
		DecodeState ds = new DecodeState(encoding);
		ACell result = read(ds);
		if (result == null) {
			if (n != 1) throw new BadFormatException("Decode of nil value but blob size = " + n);
		} else {
			long consumed = ds.pos - encoding.getInternalOffset();
			if (consumed != n) throw new BadFormatException("Excess bytes in read from Blob");
			result.attachEncoding(encoding);
		}
		return result;
	}

	/**
	 * Decodes a Ref-format encoding (as produced by {@code ref.getEncoding()}).
	 * Handles both non-embedded refs (Tag.REF + hash → store lookup) and
	 * embedded cells (inline encoding). This is the correct way to decode
	 * a "hash" parameter from the prepare/submit API.
	 *
	 * @param <T> Type of referenced value
	 * @param encoding Blob in ref format
	 * @return Ref to the decoded value
	 * @throws BadFormatException If encoding is invalid
	 */
	public <T extends ACell> Ref<T> decodeRef(Blob encoding) throws BadFormatException {
		if (encoding.count()<1) throw new BadFormatException("Empty ref encoding");
		DecodeState ds = new DecodeState(encoding);
		return readRef(ds);
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
			if (h==null) throw new BadFormatException("Insufficient bytes to read Ref");
			ds.pos += Hash.LENGTH;
			if (store==null) throw new IllegalStateException("Cannot read Ref without a store in encoder");
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
	 * Inlined for performance: decodes VLQ bytes and advances pos in a
	 * single pass (no separate length recomputation).
	 *
	 * @param ds Decode state to read from
	 * @return Decoded count value
	 * @throws BadFormatException If VLQ encoding is invalid
	 */
	public long readVLQCount(DecodeState ds) throws BadFormatException {
		byte[] data = ds.data;
		int pos = ds.pos;
		byte octet = data[pos++];
		if ((octet & 0xff) == 0x80) throw new BadFormatException("Superfluous leading zero on VLQ count");
		long result = octet & 0x7f;
		int bits = 7;
		while ((octet & 0x80) != 0) {
			if (bits > 64) throw new BadFormatException("VLQ encoding too long for long value");
			octet = data[pos++];
			result = (result << 7) | (octet & 0x7F);
			bits += 7;
		}
		if (bits > 63) throw new BadFormatException("VLQ Count overflow");
		ds.pos = pos;
		return result;
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

			case 8: // 0x80-0x8F : Data structures
				return readDataStructure(tag, ds);

			case 9: // 0x90-0x9F : Signed data
				return readSignedData(tag, ds);

			case 11: // 0xB0-0xBF : Byte flags including booleans
				return AByteFlag.read(tag);

			case 12: // 0xC0-0xCF : Coded data objects
				return readCodedData(tag, ds);

			case 13: // 0xD0-0xDF : Dense records
				return readDenseRecord(tag, ds);

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

		throw new BadFormatException(ErrorMessages.badTagMessage(tag));
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
	protected <T extends ACell> AVector<T> readVector(DecodeState ds) throws BadFormatException {
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
			if (length > Integer.MAX_VALUE) throw new BadFormatException("String length too long");
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
		if (tag == CVMTag.SYNTAX) return readSyntax(ds);
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
			
			Ref<AHashSet<V>>[] childRefs = (Ref<AHashSet<V>>[]) new Ref[ilength];
			for (int i = 0; i < ilength; i++) {
				childRefs[i] = readRef(ds);
			}
			SetTree<V> result = new SetTree<>(childRefs, shift, mask, count);
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

	private Syntax readSyntax(DecodeState ds) throws BadFormatException {
		// Syntax: datum ref + embedded props (hashmap, null encodes empty)
		Ref<ACell> datum = readRef(ds);
		@SuppressWarnings("unchecked")
		AHashMap<ACell, ACell> props = (AHashMap<ACell, ACell>) this.read(ds);
		if (props == null) {
			props = Maps.empty();
		} else {
			if (props.isEmpty()) {
				throw new BadFormatException("Empty Syntax metadata should be encoded as nil");
			}
		}
		return Syntax.createRef(datum, props);
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
	 * TODO: Consider attaching encoding on DecodeState-decoded cells. Dangerous here
	 * because the input Blob may be large (entire multi-cell message), and attaching
	 * it would pin the full byte array in memory via the top cell.
	 *
	 * @param data Multi-cell encoded data
	 * @return Decoded top-level cell
	 * @throws BadFormatException If encoding format is invalid
	 */
	@Override
	public ACell decodeMultiCell(Blob data) throws BadFormatException {
		int ml=Utils.checkedInt(data.count());
		if (ml<1) throw new BadFormatException("Attempt to decode from empty Blob");

		DecodeState ds = new DecodeState(data);

		// Select encoder. Storeless decode needs a MessageStore so readRef can
		// register branch hashes during decode; store-based uses this directly.
		CAD3Encoder readEncoder;
		HashMap<Hash,ACell> hm;
		if (store==null) {
			hm=new HashMap<>();
			MessageStore ms = new MessageStore(hm, null);
			readEncoder = withStore(ms);
			ms.setEncoder(readEncoder);
		} else {
			hm=null;
			readEncoder = this;
		}

		ACell result = readEncoder.read(ds);
		if (result==null) {
			if (ml!=1) throw new BadFormatException("Extra bytes after nil message");
			return null;
		}
		if (ds.pos==ds.limit) return result; // single cell, done

		// Multi-cell: read and resolve remaining children
		if (hm==null) hm=new HashMap<>();
		readEncoder.readChildCells(hm, ds);
		return resolveRefs(result, hm, store==null);
	}

	/**
	 * Replacement scan: resolve all non-embedded refs using decoded child cells.
	 *
	 * @param result Top-level cell to resolve
	 * @param childMap Decoded child cells from the message, keyed by hash
	 * @param failOnMissing If true, throws MissingDataException when a branch is
	 *        not in childMap. Used for storeless decode where there is no backing
	 *        store to fall back to. If false, unresolvable branches are left as-is
	 *        (they are expected to be resolvable via the backing store on demand).
	 */
	private ACell resolveRefs(ACell result, final HashMap<Hash,ACell> childMap, boolean failOnMissing) {
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

					// Branch not in message
					if (failOnMissing) {
						throw new PartialMessageException(h);
					}
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

				int childEnd = ds.pos + (int)encLength;
				if (childEnd > ds.limit) throw new BadFormatException("Child encoding exceeds message bounds");

				ACell c = this.read(ds);

				if (c==null) throw new BadFormatException("Null child encoding");
				if (c.isEmbedded()) throw new BadFormatException("Embedded Cell as child");
				if (ds.pos != childEnd) throw new BadFormatException("Child encoding length mismatch");

				// getHash() forces encoding creation, which is useful for caching later
				acc.put(c.getHash(), c);
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
		private CAD3Encoder encoder;

		MessageStore(HashMap<Hash, ACell> cells, AStore delegate) {
			this.cells = cells;
			this.delegate = delegate;
		}

		void setEncoder(CAD3Encoder encoder) {
			this.encoder = encoder;
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
		public AEncoder<ACell> getEncoder() {
			return encoder;
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
