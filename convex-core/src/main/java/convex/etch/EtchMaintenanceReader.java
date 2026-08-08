package convex.etch;

import static convex.etch.EtchConstants.POINTER_SIZE;
import static convex.etch.EtchConstants.ROOT_INDEX_SIZE;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.Objects;

import convex.core.data.AccountKey;
import convex.core.data.Hash;

/**
 * Explicit, read-only access to an Etch file for maintenance operations.
 *
 * <p>This class is unsafe only in the consistency sense: unlike normal Etch
 * opening, it permits a valid Etch v3 header whose close state is
 * {@code OPEN}. Header authentication, encryption-key verification, file
 * locking and range checks remain mandatory. It never exposes an
 * {@link EtchStore} or any mutation operation.</p>
 *
 * <p>The logical end is the last synced end selected from the v3 header (or
 * the stored length for a legacy file). Index reads are restricted to that
 * boundary. Raw reads, and decrypted candidate-data reads, may extend to the
 * physical end captured while opening so a repair tool can inspect a valid
 * post-sync tail. Callers must still validate every candidate cell and hash.</p>
 */
public final class EtchMaintenanceReader implements AutoCloseable {
	private final File file;
	private final RandomAccessFile data;
	private final FileLock lock;
	private final AEtchHeader header;
	private final EtchConfig config;
	private final AFileMapper mapper;
	private final EtchFileCipher cipher;
	private final boolean encryptedIndex;
	private final Hash rootHash;
	private final long logicalFileEnd;
	private final long physicalFileEnd;
	private final long bodyStart;
	private boolean closed;

	private EtchMaintenanceReader(File file, RandomAccessFile data, FileLock lock,
			AEtchHeader header, EtchConfig config, AFileMapper mapper,
			EtchFileCipher cipher, boolean encryptedIndex, Hash rootHash, long physicalFileEnd) {
		this.file=file;
		this.data=data;
		this.lock=lock;
		this.header=header;
		this.config=config;
		this.mapper=mapper;
		this.cipher=cipher;
		this.encryptedIndex=encryptedIndex;
		this.rootHash=rootHash;
		this.logicalFileEnd=header.storedLength();
		this.physicalFileEnd=physicalFileEnd;
		this.bodyStart=Math.addExact(header.indexStart(),(long)ROOT_INDEX_SIZE*POINTER_SIZE);
	}

	/**
	 * Opens an existing plaintext or legacy Etch file for read-only maintenance.
	 * Encrypted v3 files require {@link #openUnsafe(File, EtchConfig)}.
	 */
	public static EtchMaintenanceReader openUnsafe(File file) throws IOException {
		return openUnsafe(file,null);
	}

	/**
	 * Opens an existing Etch file for explicit read-only maintenance.
	 *
	 * @param file existing Etch file
	 * @param requestedConfig compiled options, including a key function for an
	 *        encrypted file; {@code null} permits file-inferred plaintext options
	 * @return a writer-excluding, read-only maintenance reader
	 * @throws IOException if the file, lock, header, key or configuration is invalid
	 */
	public static EtchMaintenanceReader openUnsafe(File file, EtchConfig requestedConfig)
			throws IOException {
		return open(file,requestedConfig,false);
	}

	/**
	 * Opens an existing Etch file through a read-only API while holding an
	 * exclusive lock. Reconstruction uses this form so the physical snapshot and
	 * selected root cannot change before the destination is complete.
	 */
	public static EtchMaintenanceReader openExclusive(File file,
			EtchConfig requestedConfig) throws IOException {
		return open(file,requestedConfig,true);
	}

	private static EtchMaintenanceReader open(File file, EtchConfig requestedConfig,
			boolean exclusive) throws IOException {
		Objects.requireNonNull(file,"file");
		if (!file.isFile()) throw new FileNotFoundException("Etch file does not exist: "+file);

		// Java requires a writable channel for an exclusive lock. The mapper and
		// public API remain read-only and never mutate the source.
		RandomAccessFile data=new RandomAccessFile(file,exclusive?"rw":"r");
		FileLock lock=null;
		AFileMapper mapper=null;
		AEtchHeader header=null;
		byte[] masterKey=null;
		try {
			FileChannel channel=data.getChannel();
			try {
				lock=exclusive?channel.tryLock():channel.tryLock(0L,Long.MAX_VALUE,true);
			} catch (OverlappingFileLockException e) {
				throw new IOException("File lock failed on "+file,e);
			}
			if (lock==null) throw new IOException("File lock failed on "+file);

			long physicalEnd=channel.size();
			if (physicalEnd==0L) throw new IOException("Empty Etch file: "+file);
			mapper=EtchFileMapperFactory.createExisting(channel,requestedConfig,
					file.getName(),true);
			masterKey=AEtchHeader.resolveKey(mapper,file.getName(),requestedConfig);
			header=AEtchHeader.open(mapper,file.getName(),masterKey);
			EtchConfig config=Etch.resolveExistingConfig(header,requestedConfig,file);
			long logicalEnd=header.storedLength();
			if ((logicalEnd<0L)||(logicalEnd>physicalEnd)) {
				throw new IOException("Etch stored length is outside the physical file: stored="
						+logicalEnd+" physical="+physicalEnd+" file="+file);
			}
			long bodyStart;
			try {
				bodyStart=Math.addExact(header.indexStart(),(long)ROOT_INDEX_SIZE*POINTER_SIZE);
			} catch (ArithmeticException e) {
				throw new IOException("Etch index range overflows: "+file,e);
			}
			if (bodyStart>logicalEnd) {
				throw new IOException("Etch root index extends beyond stored length: "+file);
			}

			EtchFileCipher cipher=Etch.createFileCipher(header,masterKey);
			masterKey=null; // borrowed caller-owned array is not retained
			boolean encryptedIndex=(header instanceof EtchV3Header v3)&&v3.isIndexEncrypted();
			Hash rootHash=header.getRootHash();
			EtchMaintenanceReader result=new EtchMaintenanceReader(file,data,lock,header,
					config,mapper,cipher,encryptedIndex,rootHash,physicalEnd);
			return result;
		} catch (IOException | RuntimeException | Error e) {
			closeAfterFailure(mapper,lock,data,e);
			if (header!=null) header.destroy();
			throw e;
		}
	}

	private static void closeAfterFailure(AFileMapper mapper, FileLock lock,
			RandomAccessFile data, Throwable failure) {
		if (mapper!=null) {
			try {
				mapper.close();
			} catch (IOException e) {
				failure.addSuppressed(e);
			}
		}
		if (lock!=null) {
			try {
				lock.release();
			} catch (IOException e) {
				failure.addSuppressed(e);
			}
		}
		try {
			data.close();
		} catch (IOException e) {
			failure.addSuppressed(e);
		}
	}

	public File getFile() {
		return file;
	}

	public EtchConfig getConfig() {
		return config;
	}

	public short getVersion() {
		return header.version();
	}

	public Hash getRootHash() {
		return rootHash;
	}

	public AccountKey getPublicKeyHint() {
		return header.publicKeyHint();
	}

	public long getIndexStart() {
		return header.indexStart();
	}

	/** Returns the synced v3 end, or the stored logical end for v1/v2. */
	public long getLogicalFileEnd() {
		return logicalFileEnd;
	}

	/** Returns the physical file end captured after acquiring the lock. */
	public long getPhysicalFileEnd() {
		return physicalFileEnd;
	}

	/** Returns the first byte after the fixed root index. */
	public long getBodyStart() {
		return bodyStart;
	}

	/** Returns whether only one valid v3 header copy was available. */
	public boolean isHeaderDegraded() {
		return (header instanceof EtchV3Header v3)&&v3.isDegraded();
	}

	/** Returns true only when a v3 header records a clean close. */
	public boolean isCleanClosed() {
		return (header instanceof EtchV3Header v3)&&v3.isCleanClosed();
	}

	/** Returns the selected v3 header generation, or {@code -1} for v1/v2. */
	public long getHeaderGeneration() {
		return (header instanceof EtchV3Header v3)?v3.generation():-1L;
	}

	/** Reads physical bytes without applying an encryption overlay. */
	public void readRaw(long position, byte[] destination, int offset, int length)
			throws IOException {
		checkDestination(destination,offset,length);
		checkRange(position,length,0L,physicalFileEnd,"raw read");
		mapper.read(position,destination,offset,length,null);
	}

	/**
	 * Reads and decrypts a candidate data region through the file's data overlay.
	 * The range may extend beyond the synced end, but never beyond the physical
	 * snapshot. The caller must validate the candidate cell encoding and hash.
	 */
	public void readData(long position, byte[] destination, int offset, int length)
			throws IOException {
		checkDestination(destination,offset,length);
		checkRange(position,length,bodyStart,physicalFileEnd,"candidate data read");
		mapper.read(position,destination,offset,length,cipher);
	}

	/**
	 * Reads a selected index slot with acquire ordering and index decryption.
	 * Index reads are deliberately bounded by the last synced logical end.
	 */
	public long readIndexSlot(long position) throws IOException {
		// V1's historical root index starts at byte 44. Later formats require
		// absolute 8-byte index alignment; retain lossless v1 maintenance access.
		if ((header.version()!=EtchConstants.VERSION_1)
				&&((position&(POINTER_SIZE-1L))!=0L)) {
			throw new IOException("Unaligned Etch index slot at "+position+" in "+file);
		}
		checkRange(position,POINTER_SIZE,header.indexStart(),logicalFileEnd,"index read");
		return mapper.readLongAcquire(position,encryptedIndex?cipher:null);
	}

	/**
	 * Reads and decrypts a complete candidate index range through physical EOF.
	 * Intended for exclusive reconstruction; callers must validate its slots.
	 */
	public void readIndex(long position, byte[] destination, int offset, int length)
			throws IOException {
		checkDestination(destination,offset,length);
		checkRange(position,length,header.indexStart(),physicalFileEnd,"candidate index read");
		mapper.read(position,destination,offset,length,encryptedIndex?cipher:null);
	}

	private static void checkDestination(byte[] destination, int offset, int length) {
		Objects.requireNonNull(destination,"destination");
		Objects.checkFromIndexSize(offset,length,destination.length);
	}

	private void checkRange(long position, long length, long minimum, long maximum,
			String operation) throws IOException {
		if ((position<minimum)||(length<0L)) {
			throw new IOException("Invalid Etch "+operation+" range: position="+position
					+" length="+length+" file="+file);
		}
		long end;
		try {
			end=Math.addExact(position,length);
		} catch (ArithmeticException e) {
			throw new IOException("Overflowing Etch "+operation+" range: position="+position
					+" length="+length+" file="+file,e);
		}
		if (end>maximum) {
			throw new IOException("Etch "+operation+" exceeds allowed end: end="+end
					+" allowed="+maximum+" file="+file);
		}
		ensureOpen();
	}

	private void ensureOpen() throws IOException {
		if (closed) throw new IOException("Etch maintenance reader is closed: "+file);
	}

	@Override
	public void close() throws IOException {
		if (closed) return;
		closed=true;
		IOException failure=null;
		try {
			mapper.close();
		} catch (IOException e) {
			failure=e;
		}
		try {
			lock.release();
		} catch (IOException e) {
			if (failure==null) failure=e; else failure.addSuppressed(e);
		}
		try {
			data.close();
		} catch (IOException e) {
			if (failure==null) failure=e; else failure.addSuppressed(e);
		}
		header.destroy();
		if (failure!=null) throw failure;
	}
}
