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
import convex.core.util.ErrorMessages;

/**
 * Base Encoder for CAD3 data / stores.
 * 
 * Does NOT directly decode custom CVM value types. Use the derived CVMEncoder if you need that behaviour.
 */
public class CAD3Encoder extends AEncoder<ACell> {

	public Blob encode(ACell a) {
		return Cells.encode(a);
	}
	
	@Override
	public ACell decode(Blob encoding) throws BadFormatException {
		if (encoding.count()<1) throw new BadFormatException("Empty encoding");
		return read(encoding,0);
	}
	
	@Override
	protected ACell read(Blob encoding, int offset) throws BadFormatException {
		byte tag = encoding.byteAtUnchecked(offset);
		ACell result= read(tag,encoding,offset);
		return result;
	}
	
	protected ACell read(byte tag, Blob encoding, int offset) throws BadFormatException {	
		switch (tag>>4) {
		case 0: // 0x00-0x0F : Only null is valid
			if (tag==Tag.NULL) return null;
			break;
			
		case 1: // 0x10-0x1F : Numeric values 
			return readNumeric(tag,encoding,offset);
			
		case 2: // 0x20-0x2F : Addresses and references 
			if (tag == CVMTag.ADDRESS) return Address.read(encoding,offset);
			// Note: 0x20 reference is invalid as a top level encoding
			break;

		case 3: // 0x30-0x3F : BAsic string / blob-like objects
			return readBasicObject(tag, encoding, offset);
			
		case 4: case 5: case 6: case 7: // 0x40-0x7F currently reserved
			break;
			
		case 8: // 0x80-0x8F : BAsic string / blob-like objects
			return readDataStructure(tag, encoding, offset);
			
		case 9: // 0x90-0x9F : Crypto / signature objects
			return readSignedData(tag,encoding, offset);

		case 10: // 0xA0-0xAF : Sparse records. A is for Airy.
			return readSparseRecord(tag,encoding,offset);

		case 11: // 0xB0-0xBF : Byte flags including booleans
			return AByteFlag.read(tag);
			
		case 12: // 0xC0-0xCF : Coded data objects
			return readCodedData(tag,encoding, offset);

		case 13: // 0xD0-0xDF : Dense records
			return readDenseRecord(tag,encoding, offset);

		case 14: // 0xE0-0xEF : Extension values
			return readExtension(tag,encoding, offset);

		case 15: // 0xF0-0xFF : Reserved / failure  Values
			break;
		}
		
		return Format.read(tag,encoding,offset);
		// throw new BadFormatException(ErrorMessages.badTagMessage(tag));
	}


	protected ANumeric readNumeric(byte tag, Blob blob, int offset) throws BadFormatException {
		if (tag<0x19) return CVMLong.read(tag,blob,offset);
		if (tag == 0x19) return CVMBigInteger.read(blob,offset);
		if (tag == Tag.DOUBLE) return CVMDouble.read(tag,blob,offset);
		
		throw new BadFormatException(ErrorMessages.badTagMessage(tag));
	}
	
	protected ACell readBasicObject(byte tag, Blob blob, int offset)  throws BadFormatException{
		switch (tag) {
			case Tag.SYMBOL: return Symbol.read(blob,offset);
			case Tag.KEYWORD: return Keyword.read(blob,offset);
			case Tag.BLOB: return Blobs.read(blob,offset);
			case Tag.STRING: return Strings.read(blob,offset);
		} 
		
		if ((tag&Tag.CHAR_MASK)==Tag.CHAR_BASE) {
			int len=CVMChar.byteCountFromTag(tag);
			if (len>4) throw new BadFormatException("Can't read char type with length: " + len);
			return CVMChar.read(len, blob,offset); // skip tag byte
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
	
	protected SignedData<?> readSignedData(byte tag,Blob blob, int offset) throws BadFormatException {
		if (tag==Tag.SIGNED_DATA) return SignedData.read(blob,offset,true);	
		if (tag==Tag.SIGNED_DATA_SHORT) return SignedData.read(blob,offset,false);	
		throw new BadFormatException(ErrorMessages.badTagMessage(tag));
	}
	
	protected ARecord<?,?> readSparseRecord(byte tag, Blob encoding, int offset) throws BadFormatException {
		// TODO spare records
		throw new BadFormatException(ErrorMessages.TODO);
	}
	
	protected ACell readCodedData(byte tag, Blob encoding, int offset) throws BadFormatException {
		// TODO Change delegation to proper read
		return Format.read(tag,encoding,offset);
	}

	protected ACell readDenseRecord(byte tag, Blob encoding, int offset) throws BadFormatException {
		// TODO Change delegation to proper read
		return Format.read(tag,encoding,offset);
	}
	
	protected ACell readExtension(byte tag, Blob blob, int offset) throws BadFormatException {
		// We expect a VLQ Count following the tag
		long code=Format.readVLQCount(blob,offset+1);
		return ExtensionValue.create(tag, code);
	}




	/**
	 * Reads a cell value from a Blob of data, allowing for non-embedded branches following the first cell
	 * @param data Data to decode
	 * @return Cell instance
	 * @throws BadFormatException If CAD3 encoding format is invalid
	 */
	@Override
	public ACell decodeMultiCell(Blob enc) throws BadFormatException  {
		return Format.decodeMultiCell(enc);
	}
}
