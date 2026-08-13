package convex.etch;

import static convex.etch.EtchConstants.ENCODING_LENGTH_SIZE;
import static convex.etch.EtchConstants.KEY_SIZE;
import static convex.etch.EtchConstants.LABEL_SIZE;

import java.io.IOException;

import convex.core.crypto.Hashing;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Hash;
import convex.core.exceptions.BadFormatException;
import convex.core.exceptions.InvalidDataException;
import convex.core.store.AStore;
import convex.core.util.Utils;

/** Shared strict verification of a single Etch data record. */
final class EtchRecordVerifier {
	static final int RECORD_HEADER_SIZE=KEY_SIZE+LABEL_SIZE+ENCODING_LENGTH_SIZE;
	static final int MAX_RECORD_SIZE=RECORD_HEADER_SIZE+Format.LIMIT_ENCODING_LENGTH;

	enum FailureKind {
		EXTENT, LENGTH, HASH, ENCODING, CANONICAL, IO
	}

	record Failure(FailureKind kind, String message) {
	}

	record Verified(long position, int recordLength, Hash hash, ACell cell,
			Blob encoding) {
	}

	record Result(Verified verified, Failure failure) {
		static Result success(Verified verified) {
			return new Result(verified,null);
		}

		static Result failure(FailureKind kind, String message) {
			return new Result(null,new Failure(kind,message));
		}

		boolean isValid() {
			return verified!=null;
		}
	}

	private final EtchMaintenanceReader source;
	private final AStore decoder;
	private final byte[] header=new byte[RECORD_HEADER_SIZE];

	EtchRecordVerifier(EtchMaintenanceReader source, AStore decoder) {
		this.source=source;
		this.decoder=decoder;
	}

	Result verify(long position, long maximumEnd) {
		long headerEnd;
		try {
			headerEnd=Math.addExact(position,RECORD_HEADER_SIZE);
		} catch (ArithmeticException e) {
			return Result.failure(FailureKind.EXTENT,"record header overflows at "+position);
		}
		if ((position<source.getBodyStart())||(headerEnd>maximumEnd)) {
			return Result.failure(FailureKind.EXTENT,"record header is outside the selected file at "+position);
		}

		try {
			source.readData(position,header,0,header.length);
		} catch (IOException | RuntimeException | Error e) {
			if (e instanceof VirtualMachineError fatal) throw fatal;
			return Result.failure(FailureKind.IO,"cannot read record header at "+position+": "+e.getMessage());
		}

		int length=Utils.readShort(header,KEY_SIZE+LABEL_SIZE)&0xffff;
		if ((length==0)||(length>Format.LIMIT_ENCODING_LENGTH)) {
			return Result.failure(FailureKind.LENGTH,"invalid encoding length "+length+" at "+position);
		}
		long recordEnd;
		try {
			recordEnd=Math.addExact(headerEnd,length);
		} catch (ArithmeticException e) {
			return Result.failure(FailureKind.EXTENT,"record extent overflows at "+position);
		}
		if (recordEnd>maximumEnd) {
			return Result.failure(FailureKind.EXTENT,"record exceeds the selected file at "+position);
		}

		byte[] encoding=new byte[length];
		try {
			source.readData(headerEnd,encoding,0,length);
		} catch (IOException | RuntimeException | Error e) {
			if (e instanceof VirtualMachineError fatal) throw fatal;
			return Result.failure(FailureKind.IO,"cannot read record encoding at "+position+": "+e.getMessage());
		}
		return verify(position,Hash.wrap(header,0),Blob.wrap(encoding));
	}

	Result verify(long position, Hash storedHash, Blob encoding) {
		Hash actualHash=Hashing.sha3(encoding);
		if (!storedHash.equals(actualHash)) {
			return Result.failure(FailureKind.HASH,"content hash mismatch at "+position);
		}
		// Use the independently computed, owned hash from here on. The supplied key
		// may be a view over a reusable maintenance buffer.
		storedHash=actualHash;

		ACell cell;
		try {
			cell=decoder.decode(encoding);
			if (cell!=null) cell.validateStructure();
		} catch (BadFormatException | InvalidDataException | RuntimeException | Error e) {
			if (e instanceof VirtualMachineError fatal) throw fatal;
			return Result.failure(FailureKind.ENCODING,"invalid CAD3 encoding at "+position+": "+e.getMessage());
		}
		byte[] canonical=new byte[(int)encoding.count()];
		int end;
		try {
			if (cell==null) {
				canonical[0]=Blob.NULL_ENCODING.byteAt(0);
				end=1;
			} else {
				// Deliberately bypass getEncoding(): decode attaches the source bytes to
				// the top-level cell, which cannot prove that those bytes are canonical.
				end=cell.encode(canonical,0);
			}
		} catch (RuntimeException | Error e) {
			if (e instanceof VirtualMachineError fatal) throw fatal;
			return Result.failure(FailureKind.CANONICAL,"cannot canonically encode record at "+position+": "+e.getMessage());
		}
		if ((end!=canonical.length)||!Blob.wrap(canonical).equals(encoding)) {
			return Result.failure(FailureKind.CANONICAL,"non-canonical CAD3 encoding at "+position);
		}
		return Result.success(new Verified(position,RECORD_HEADER_SIZE+canonical.length,
				storedHash,cell,encoding));
	}
}
