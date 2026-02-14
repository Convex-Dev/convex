package convex.core.data;

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
import convex.core.util.ErrorMessages;
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
}
