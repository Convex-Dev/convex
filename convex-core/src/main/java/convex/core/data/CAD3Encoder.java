package convex.core.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

import convex.core.cvm.Address;
import convex.core.cvm.CVMTag;
import convex.core.cvm.Syntax;
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

	public CAD3Encoder() {}

	@Override
	public Blob encode(ACell a) {
		return Cells.encode(a);
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

	// ==================== Multi-cell decode ====================

	/**
	 * Decodes a cell from multi-cell encoded data. The top-level cell is
	 * followed by VLQ-prefixed child cells. All reads dispatch through this
	 * encoder's virtual {@link #read} methods, so CAD3Encoder produces generic
	 * types and CVMEncoder produces CVM-specific types.
	 *
	 * Uses the current thread-local store for Ref resolution. If no store is
	 * set, installs a temporary MessageStore for the duration of child decoding.
	 *
	 * @param data Multi-cell encoded data
	 * @return Decoded top-level cell
	 * @throws BadFormatException If encoding format is invalid
	 */
	@SuppressWarnings("unchecked")
	@Override
	public ACell decodeMultiCell(Blob data) throws BadFormatException {
		int ml=Utils.checkedInt(data.count());
		if (ml<1) throw new BadFormatException("Attempt to decode from empty Blob");

		// Read first cell unconditionally. Caller must set a store if needed.
		ACell result= this.read(data,0);
		if (result==null) {
			if (ml!=1) throw new BadFormatException("Extra bytes after nil message");
			return null;
		}

		int rl=Utils.checkedInt(result.getEncodingLength());
		if (rl==ml) return result; // Single-cell fast path: no HashMap needed

		// Multi-cell: decode children into HashMap
		HashMap<Hash,ACell> hm=new HashMap<>();
		boolean replacedStore=false;
		if (Stores.current()==null) {
			Stores.setCurrent(new MessageStore(hm, null));
			replacedStore=true;
		}

		try {
			readChildCells(hm, data, rl, ml);

			// Replacement scan: resolve all refs from decoded children
			final HashMap<Hash,ACell> childMap=hm;
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

			result=done.get(result.getHash());
			return result;
		} finally {
			if (replacedStore) Stores.setCurrent(null);
		}
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

				Blob enc=data.slice(ix, ix+(int)encLength);
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
