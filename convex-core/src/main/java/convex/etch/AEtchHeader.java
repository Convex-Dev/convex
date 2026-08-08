package convex.etch;

import static convex.etch.EtchConstants.MAGIC_NUMBER;
import static convex.etch.EtchConstants.V3_HEADER_B_OFFSET;
import static convex.etch.EtchConstants.V3_HEADER_COPY_SIZE;
import static convex.etch.EtchConstants.VERSION_1;
import static convex.etch.EtchConstants.VERSION_2;
import static convex.etch.EtchConstants.VERSION_3;

import java.io.IOException;

import convex.core.data.AccountKey;
import convex.core.data.Hash;

/**
 * Cold-path Etch header behaviour selected once from the file version.
 *
 * <p>Normal index and data operations do not pass through this abstraction.
 * It owns only file initialisation and metadata operations whose persistence
 * rules differ between Etch versions.</p>
 */
abstract class AEtchHeader {
	private final short version;
	private final long indexStart;
	private final long storedLength;

	AEtchHeader(short version, long indexStart, long storedLength) {
		this.version=version;
		this.indexStart=indexStart;
		this.storedLength=storedLength;
	}

	static AEtchHeader create(EtchConfig config, byte[] masterKey) throws IOException {
		short version=config.getVersion();
		return switch (version) {
			case VERSION_1, VERSION_2 -> LegacyEtchHeader.create(version);
			case VERSION_3 -> EtchV3Header.create(config,masterKey);
			default -> throw unsupported(version);
		};
	}

	/** Resolves an encrypted v3 master key from plaintext header fields. */
	static byte[] resolveKey(AFileMapper mapper, String fileName, EtchConfig config)
			throws IOException {
		byte[] probe=readProbe(mapper,0L);
		int magic=readUnsignedShort(probe,0);
		short version=(short)readUnsignedShort(probe,Short.BYTES);
		boolean v3=(magic==MAGIC_NUMBER)&&(version==VERSION_3);
		if (!v3) v3=hasV3Probe(mapper,V3_HEADER_B_OFFSET);
		if (!v3) return null;
		byte[] copyA=readCopy(mapper,0L);
		byte[] copyB=readCopy(mapper,V3_HEADER_B_OFFSET);
		return EtchV3Header.resolveKey(copyA,copyB,config,fileName);
	}

	static AEtchHeader open(AFileMapper mapper, String fileName, byte[] masterKey)
			throws IOException {
		byte[] probe=readProbe(mapper,0L);
		int magic=readUnsignedShort(probe,0);
		short version=(short)readUnsignedShort(probe,Short.BYTES);
		if (magic==MAGIC_NUMBER) {
			if (version==VERSION_3) return openV3(mapper,fileName,masterKey);
		}

		// A damaged v3 copy A must not hide a valid independent copy B, even
		// when the damaged probe happens to resemble a supported legacy version.
		if (hasV3Probe(mapper,V3_HEADER_B_OFFSET)) {
			return openV3(mapper,fileName,masterKey);
		}

		if ((magic==MAGIC_NUMBER)&&((version==VERSION_1)||(version==VERSION_2))) {
			return LegacyEtchHeader.open(mapper,fileName);
		}

		if (magic!=MAGIC_NUMBER) {
			throw new IOException("Bad magic number! Probably not an Etch file: "+fileName);
		}
		throw unsupported(version);
	}

	private static boolean hasV3Probe(AFileMapper mapper, long position) throws IOException {
		if (mapper.length()<position+Short.BYTES*2L) return false;
		byte[] probe=readProbe(mapper,position);
		return (readUnsignedShort(probe,0)==MAGIC_NUMBER)
				&&((short)readUnsignedShort(probe,Short.BYTES)==VERSION_3);
	}

	private static EtchV3Header openV3(AFileMapper mapper, String fileName,
			byte[] masterKey) throws IOException {
		byte[] copyA=readCopy(mapper,0L);
		byte[] copyB=readCopy(mapper,V3_HEADER_B_OFFSET);
		return EtchV3Header.select(copyA,copyB,masterKey,fileName);
	}

	private static byte[] readCopy(AFileMapper mapper, long position) throws IOException {
		long available=Math.max(0L,Math.min(V3_HEADER_COPY_SIZE,mapper.length()-position));
		byte[] copy=new byte[Math.toIntExact(available)];
		mapper.read(position,copy,0,copy.length,null);
		return copy;
	}

	private static byte[] readProbe(AFileMapper mapper, long position) throws IOException {
		if (mapper.length()<position+Short.BYTES*2L) {
			throw new IOException("Truncated Etch format probe at "+position);
		}
		byte[] probe=new byte[Short.BYTES*2];
		mapper.read(position,probe,0,probe.length,null);
		return probe;
	}

	private static int readUnsignedShort(byte[] source, int offset) {
		return ((source[offset]&0xff)<<8)|(source[offset+1]&0xff);
	}

	private static IOException unsupported(short version) {
		return new IOException("Unsupported Etch version: "+version);
	}

	final short version() {
		return version;
	}

	final long indexStart() {
		return indexStart;
	}

	final long storedLength() {
		return storedLength;
	}

	abstract AccountKey publicKeyHint();

	abstract void initialise(Etch etch) throws IOException;

	abstract Hash getRootHash();

	abstract void setRootHash(Etch etch, Hash rootHash) throws IOException;

	abstract void prepareMutation(Etch etch) throws IOException;

	abstract void writeDataLength(Etch etch) throws IOException;

	abstract void sync(Etch etch) throws IOException;

	abstract void close(Etch etch) throws IOException;

	/** Wipes header-owned key material without writing to the file. */
	void destroy() {
		// Legacy and plaintext header implementations own no key material.
	}
}
