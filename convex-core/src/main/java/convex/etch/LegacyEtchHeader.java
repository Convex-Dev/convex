package convex.etch;

import static convex.etch.EtchConstants.DATA_LENGTH_OFFSET;
import static convex.etch.EtchConstants.MAGIC_NUMBER;
import static convex.etch.EtchConstants.POINTER_SIZE;
import static convex.etch.EtchConstants.ROOT_HASH_OFFSET;
import static convex.etch.EtchConstants.ROOT_INDEX_SIZE;
import static convex.etch.EtchConstants.VERSION_1;
import static convex.etch.EtchConstants.VERSION_2;
import static convex.etch.EtchConstants.VERSION_OFFSET;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;

import convex.core.data.AccountKey;
import convex.core.data.Hash;
import convex.core.util.Utils;

/** Existing single-header Etch v1/v2 metadata behaviour. */
final class LegacyEtchHeader extends EtchHeader {
	private LegacyEtchHeader(short version, long storedLength) {
		super(version,indexStart(version),storedLength);
	}

	static LegacyEtchHeader create(short version) {
		return new LegacyEtchHeader(version,0L);
	}

	static LegacyEtchHeader open(RandomAccessFile data, short version) throws IOException {
		return new LegacyEtchHeader(version,data.readLong());
	}

	/** Decodes the fixed legacy prefix, primarily for byte-level format tests. */
	static LegacyEtchHeader decode(byte[] source, String fileName) throws IOException {
		if (source.length<DATA_LENGTH_OFFSET+Long.BYTES) {
			throw new IOException("Truncated Etch header: "+fileName);
		}
		int magic=Utils.readShort(source,0)&0xffff;
		if (magic!=MAGIC_NUMBER) {
			throw new IOException("Bad magic number! Probably not an Etch file: "+fileName);
		}
		short version=Utils.readShort(source,(int)VERSION_OFFSET);
		if ((version!=VERSION_1)&&(version!=VERSION_2)) {
			throw new IOException("Unsupported legacy Etch version: "+version);
		}
		return new LegacyEtchHeader(version,
				Utils.readLong(source,(int)DATA_LENGTH_OFFSET,Long.BYTES));
	}

	private static long indexStart(short version) {
		return switch (version) {
			case VERSION_1 -> EtchConstants.V1_INDEX_START;
			case VERSION_2 -> EtchConstants.V2_INDEX_START;
			default -> throw new IllegalArgumentException("Unsupported legacy Etch version: "+version);
		};
	}

	@Override
	AccountKey publicKeyHint() {
		return null;
	}

	@Override
	void initialise(EtchFileAccess access) throws IOException {
		if (access.getDataLength()!=0L) {
			throw new IllegalStateException("Cannot initialise a non-empty Etch file");
		}
		byte[] initialHeader=new byte[Math.toIntExact(indexStart())];
		Utils.writeShort(initialHeader,0,(short)MAGIC_NUMBER);
		Utils.writeShort(initialHeader,(int)VERSION_OFFSET,version());
		long headerPosition=access.appendHeader(initialHeader,0,initialHeader.length);
		if (headerPosition!=0L) throw new IllegalStateException("Unexpected Etch header position");

		int rootLength=ROOT_INDEX_SIZE*POINTER_SIZE;
		long rootPosition=access.appendZeroIndex(rootLength,1);
		if (rootPosition!=indexStart()) {
			throw new IllegalStateException("Unexpected Etch root index position: "+rootPosition);
		}
		writeDataLength(access);
	}

	@Override
	Hash getRootHash(EtchFileAccess access) throws IOException {
		byte[] bytes=new byte[Hash.LENGTH];
		access.readHeader(ROOT_HASH_OFFSET,bytes,0,bytes.length);
		if (Arrays.equals(bytes,Utils.ZERO_BYTES_32)) return Hash.UNSET_HASH;
		return Hash.wrap(bytes);
	}

	@Override
	void setRootHash(EtchFileAccess access, Hash rootHash) throws IOException {
		byte[] bytes=rootHash.getBytes();
		access.writeHeader(ROOT_HASH_OFFSET,bytes,0,bytes.length);
	}

	@Override
	void prepareMutation(EtchFileAccess access) {
		// Legacy headers have no writing-session marker.
	}

	@Override
	void writeDataLength(EtchFileAccess access) throws IOException {
		byte[] bytes=new byte[Long.BYTES];
		Utils.writeLong(bytes,0,access.getDataLength());
		access.writeHeader(DATA_LENGTH_OFFSET,bytes,0,bytes.length);
	}

	@Override
	void sync(EtchFileAccess access) throws IOException {
		access.force();
	}

	@Override
	void close(EtchFileAccess access) throws IOException {
		writeDataLength(access);
		sync(access);
	}
}
