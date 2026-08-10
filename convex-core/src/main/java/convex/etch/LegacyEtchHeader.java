package convex.etch;

import static convex.etch.EtchConstants.DATA_LENGTH_OFFSET;
import static convex.etch.EtchConstants.MAGIC_NUMBER;
import static convex.etch.EtchConstants.ROOT_HASH_OFFSET;
import static convex.etch.EtchConstants.VERSION_1;
import static convex.etch.EtchConstants.VERSION_2;
import static convex.etch.EtchConstants.VERSION_OFFSET;

import java.io.IOException;
import java.util.Arrays;

import convex.core.data.AccountKey;
import convex.core.data.Hash;
import convex.core.util.Utils;

/** Existing single-header Etch v1/v2 metadata behaviour. */
final class LegacyEtchHeader extends AEtchHeader {
	private Hash rootHash;

	private LegacyEtchHeader(short version, long storedLength, Hash rootHash) {
		super(version,indexStart(version),storedLength);
		this.rootHash=rootHash;
	}

	static LegacyEtchHeader create(short version) {
		return new LegacyEtchHeader(version,0L,Hash.UNSET_HASH);
	}

	static LegacyEtchHeader open(AFileMapper mapper, String fileName) throws IOException {
		int headerLength=Math.toIntExact(ROOT_HASH_OFFSET+Hash.LENGTH);
		if (mapper.length()<headerLength) throw new IOException("Truncated Etch header: "+fileName);
		byte[] header=new byte[headerLength];
		mapper.read(0L,header,0,header.length,null);
		return decode(header,fileName);
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
		if (source.length<ROOT_HASH_OFFSET+Hash.LENGTH) {
			throw new IOException("Truncated Etch root hash: "+fileName);
		}
		byte[] rootBytes=Arrays.copyOfRange(source,(int)ROOT_HASH_OFFSET,
				(int)ROOT_HASH_OFFSET+Hash.LENGTH);
		Hash rootHash=Arrays.equals(rootBytes,Utils.ZERO_BYTES_32)
				?Hash.UNSET_HASH:Hash.wrap(rootBytes);
		return new LegacyEtchHeader(version,
				Utils.readLong(source,(int)DATA_LENGTH_OFFSET,Long.BYTES),rootHash);
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
	void initialise(Etch etch) throws IOException {
		if (etch.getDataLength()!=0L) {
			throw new IllegalStateException("Cannot initialise a non-empty Etch file");
		}
		byte[] initialHeader=new byte[Math.toIntExact(indexStart())];
		Utils.writeShort(initialHeader,0,(short)MAGIC_NUMBER);
		Utils.writeShort(initialHeader,(int)VERSION_OFFSET,version());
		long headerPosition=etch.appendHeader(initialHeader,0,initialHeader.length);
		if (headerPosition!=0L) throw new IllegalStateException("Unexpected Etch header position");

		long rootPosition=etch.appendNewIndexBlock(0);
		if (rootPosition!=indexStart()) {
			throw new IllegalStateException("Unexpected Etch root index position: "+rootPosition);
		}
		writeDataLength(etch);
	}

	@Override
	Hash getRootHash() {
		return rootHash;
	}

	@Override
	void setRootHash(Etch etch, Hash rootHash) throws IOException {
		byte[] bytes=rootHash.getBytes();
		etch.writeHeader(ROOT_HASH_OFFSET,bytes,0,bytes.length);
		this.rootHash=rootHash;
	}

	@Override
	void prepareMutation(Etch etch) {
		// Legacy headers have no writing-session marker.
	}

	@Override
	void writeDataLength(Etch etch) throws IOException {
		byte[] bytes=new byte[Long.BYTES];
		Utils.writeLong(bytes,0,etch.getDataLength());
		etch.writeHeader(DATA_LENGTH_OFFSET,bytes,0,bytes.length);
	}

	@Override
	void sync(Etch etch) throws IOException {
		etch.force();
	}

	@Override
	void close(Etch etch) throws IOException {
		writeDataLength(etch);
		sync(etch);
	}
}
