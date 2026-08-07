package convex.etch;

import static convex.etch.EtchConstants.MAGIC_NUMBER;
import static convex.etch.EtchConstants.VERSION_1;
import static convex.etch.EtchConstants.VERSION_2;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;

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

	static EtchHeader create(EtchConfig config) throws IOException {
		short version=config.getVersion();
		return switch (version) {
			case VERSION_1, VERSION_2 -> LegacyEtchHeader.create(version);
			default -> throw unsupported(version);
		};
	}

	static EtchHeader open(RandomAccessFile data, String fileName) throws IOException {
		data.seek(0L);
		int magic=data.readUnsignedShort();
		if (magic!=MAGIC_NUMBER) {
			throw new IOException("Bad magic number! Probably not an Etch file: "+fileName);
		}
		short version=data.readShort();
		return switch (version) {
			case VERSION_1, VERSION_2 -> LegacyEtchHeader.open(data,version);
			default -> throw unsupported(version);
		};
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

	abstract void initialise(EtchFileAccess access) throws IOException;

	abstract Hash getRootHash(EtchFileAccess access) throws IOException;

	abstract void setRootHash(EtchFileAccess access, Hash rootHash) throws IOException;

	abstract void writeDataLength(EtchFileAccess access) throws IOException;

	abstract void sync(EtchFileAccess access, FileChannel channel) throws IOException;

	abstract void close(EtchFileAccess access, FileChannel channel) throws IOException;
}
