package convex.etch;

import static convex.etch.EtchConstants.MAGIC_NUMBER;
import static convex.etch.EtchConstants.V3_HEADER_B_OFFSET;
import static convex.etch.EtchConstants.V3_HEADER_COPY_SIZE;
import static convex.etch.EtchConstants.VERSION_1;
import static convex.etch.EtchConstants.VERSION_2;
import static convex.etch.EtchConstants.VERSION_3;

import java.io.IOException;
import java.io.RandomAccessFile;

import convex.core.data.AccountKey;
import convex.core.data.Hash;

/**
 * Cold-path Etch header behaviour selected once from the file version.
 *
 * <p>Normal index and data operations do not pass through this abstraction.
 * It owns only file initialisation and metadata operations whose persistence
 * rules differ between Etch versions.</p>
 */
abstract class EtchHeader {
	private final short version;
	private final long indexStart;
	private final long storedLength;

	EtchHeader(short version, long indexStart, long storedLength) {
		this.version=version;
		this.indexStart=indexStart;
		this.storedLength=storedLength;
	}

	static EtchHeader create(EtchConfig config, byte[] masterKey) throws IOException {
		short version=config.getVersion();
		return switch (version) {
			case VERSION_1, VERSION_2 -> LegacyEtchHeader.create(version);
			case VERSION_3 -> EtchV3Header.create(config,masterKey);
			default -> throw unsupported(version);
		};
	}

	/** Resolves an encrypted v3 master key from plaintext header fields. */
	static byte[] resolveKey(RandomAccessFile data, String fileName, EtchConfig config)
			throws IOException {
		data.seek(0L);
		int magic=data.readUnsignedShort();
		short version=data.readShort();
		boolean v3=(magic==MAGIC_NUMBER)&&(version==VERSION_3);
		if (!v3) v3=hasV3Probe(data,V3_HEADER_B_OFFSET);
		if (!v3) return null;
		byte[] copyA=readCopy(data,0L);
		byte[] copyB=readCopy(data,V3_HEADER_B_OFFSET);
		return EtchV3Header.resolveKey(copyA,copyB,config,fileName);
	}

	static EtchHeader open(RandomAccessFile data, String fileName, byte[] masterKey) throws IOException {
		data.seek(0L);
		int magic=data.readUnsignedShort();
		short version=data.readShort();
		if (magic==MAGIC_NUMBER) {
			if (version==VERSION_3) return openV3(data,fileName,masterKey);
		}

		// A damaged v3 copy A must not hide a valid independent copy B, even
		// when the damaged probe happens to resemble a supported legacy version.
		if (hasV3Probe(data,V3_HEADER_B_OFFSET)) {
			return openV3(data,fileName,masterKey);
		}

		if ((magic==MAGIC_NUMBER)&&((version==VERSION_1)||(version==VERSION_2))) {
			data.seek(Short.BYTES*2L);
			return LegacyEtchHeader.open(data,version);
		}

		if (magic!=MAGIC_NUMBER) {
			throw new IOException("Bad magic number! Probably not an Etch file: "+fileName);
		}
		throw unsupported(version);
	}

	private static boolean hasV3Probe(RandomAccessFile data, long position) throws IOException {
		if (data.length()<position+Short.BYTES*2L) return false;
		data.seek(position);
		return (data.readUnsignedShort()==MAGIC_NUMBER)&&(data.readShort()==VERSION_3);
	}

	private static EtchV3Header openV3(RandomAccessFile data, String fileName,
			byte[] masterKey) throws IOException {
		byte[] copyA=readCopy(data,0L);
		byte[] copyB=readCopy(data,V3_HEADER_B_OFFSET);
		return EtchV3Header.select(copyA,copyB,masterKey,fileName);
	}

	private static byte[] readCopy(RandomAccessFile data, long position) throws IOException {
		long available=Math.max(0L,Math.min(V3_HEADER_COPY_SIZE,data.length()-position));
		byte[] copy=new byte[Math.toIntExact(available)];
		data.seek(position);
		data.readFully(copy);
		return copy;
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

	abstract void initialise(EtchFileAccess access) throws IOException;

	abstract Hash getRootHash(EtchFileAccess access) throws IOException;

	abstract void setRootHash(EtchFileAccess access, Hash rootHash) throws IOException;

	abstract void prepareMutation(EtchFileAccess access) throws IOException;

	abstract void writeDataLength(EtchFileAccess access) throws IOException;

	abstract void sync(EtchFileAccess access) throws IOException;

	abstract void close(EtchFileAccess access) throws IOException;

	/** Wipes header-owned key material without writing to the file. */
	void destroy() {
		// Legacy and plaintext header implementations own no key material.
	}
}
