package convex.etch;

import static convex.etch.EtchConstants.*;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.Arrays;

import convex.core.Constants;
import convex.core.data.AArrayBlob;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.data.RefSoft;
import convex.core.exceptions.BadFormatException;
import convex.core.util.Counters;
import convex.core.util.Shutdown;
import convex.core.util.Utils;

/**
 * A stupid, fast database for immutable data you want carved in stone.
 *
 * We solve the cache invalidation problem, quite effectively, by never changing anything. Once a value
 * is written for a given key, it cannot be changed. Etch is indifferent to the exact meaning of keys,
 * but they must have a fixed length of 32 bytes (256 bits).
 *
 * It is intended that keys are pseudo-random hash values, which will result in desirable distributions
 * of data for the radix tree structure.
 *
 * Radix tree index blocks are arrays of 8 byte pointers.
 *
 * To avoid creating too many index blocks when collisions occur, a chained entry list inside is created
 * in unused space in index blocks. Once there is no more space, chains are collapsed to a new index block.
 *
 * Common header fields are as follows:
 * - Magic number 0xe7c6 (2 bytes)
 * - Etch format version (2 bytes)
 * - Database length in bytes (8 bytes)
 * - Root hash (32 bytes)
 *
 * Etch v1 starts its root index immediately afterwards at byte 44. Etch v2
 * reserves the remainder of a 64-byte header and starts its aligned root index
 * at byte 64.
 * 
 * Pointers in index blocks are of 4 possible types, determined by the two high bits (MSBs):
 * - 00 high bits: pointer to data
 * - 01 high bits: pointer to next index node
 * - 10 high bits: start of chained entry list
 * - 11 high bits: continuation of chained entry list
 *
 * Data is stored as:
 * - 32 bytes key
 * - X bytes monotonic label of which
 *    - 1 byte status
 *    - 8 bytes Memory Size 
 * - 2 bytes data length N (a short)
 * - N byes actual data
 */
public class Etch {
	/**
	 * Temporary byte array on a thread local basis.
	 */
	private final ThreadLocal<byte[]> tempArray=new ThreadLocal<>() {
		@Override
		public byte[]  initialValue() {
			return new byte[2048];
		}
	};

	/**
	 * Internal pointer to end of database
	 */
	private static long tempIndex=0;

	private final File file;
	private final String fileName;
	private final RandomAccessFile data;

	private final EtchConfig config;
	private final EtchHeader header;
	private final short version;
	private final long indexStart;
	private final EtchFileAccess fileAccess;
	private final boolean buildChains;
	private boolean requiresOpenTransition;

	private EtchStore store;

	private Etch(File dataFile) throws IOException {
		this(dataFile,null);
	}

	private Etch(File dataFile, EtchConfig requestedConfig) throws IOException {
		// Ensure we have a RandomAccessFile that exists
		this.file=dataFile;
		if (!dataFile.exists()) dataFile.createNewFile();
		this.data=new RandomAccessFile(dataFile,"rw");

		this.fileName = dataFile.getName();
		EtchFileMapper mapper=null;
		EtchFileAccess access=null;
		try {
			// Try to exclusively lock the Etch database file
			FileChannel fileChannel=this.data.getChannel();
			FileLock lock;
			try {
				lock=fileChannel.tryLock();
			} catch (OverlappingFileLockException e) {
				throw new IOException("File lock failed on "+dataFile,e);
			}
			if (lock==null) {
				throw new IOException("File lock failed on "+dataFile);
			}
			// at this point, we have an exclusive lock on the database file.
			boolean newFile=(dataFile.length()==0);
			EtchHeader resolvedHeader;
			EtchConfig effectiveConfig=null;
			if (newFile) {
				effectiveConfig=(requestedConfig==null)?EtchConfig.create():requestedConfig;
				resolvedHeader=EtchHeader.create(effectiveConfig);
			} else {
				byte[] openSecret=(requestedConfig==null)?null:requestedConfig.encryptionSecret();
				try {
					resolvedHeader=EtchHeader.open(this.data,fileName,openSecret);
				} finally {
					if (openSecret!=null) Arrays.fill(openSecret,(byte)0);
				}
			}

			short fileVersion=resolvedHeader.version();
			if (!newFile) {
				if (resolvedHeader instanceof EtchV3Header v3Header) {
					if (!v3Header.isCleanClosed()) {
						throw new IOException("Etch v3 file was not cleanly closed; explicit recovery or repair required: "
								+dataFile);
					}
				}
				effectiveConfig=resolveExistingConfig(resolvedHeader,requestedConfig,dataFile);
			}

			this.config=effectiveConfig;
			this.header=resolvedHeader;
			this.version=fileVersion;
			this.indexStart=resolvedHeader.indexStart();
			this.buildChains=effectiveConfig.isBuildChains();
			this.requiresOpenTransition=!newFile&&(resolvedHeader instanceof EtchV3Header);
			mapper=EtchFileMapperFactory.create(fileChannel,effectiveConfig.getMappingMode());
			EtchFileCipher cipher=createFileCipher(resolvedHeader,effectiveConfig);
			boolean encryptedIndex=false;
			if (resolvedHeader instanceof EtchV3Header v3Header) {
				encryptedIndex=v3Header.isIndexEncrypted();
			}
			access=new EtchFileAccess(mapper,fileName,newFile?0L:resolvedHeader.storedLength(),
					fileChannel.size(),cipher,encryptedIndex);
			this.fileAccess=access;

			if (newFile) {
				header.initialise(fileAccess);
			}

			// shutdown hook to close file / release lock
			convex.core.util.Shutdown.addHook(Shutdown.ETCH,this::close);
		} catch (IOException | RuntimeException | Error e) {
			if (access!=null) {
				try {
					access.close();
				} catch (IOException closeException) {
					e.addSuppressed(closeException);
				}
			} else if (mapper!=null) {
				try {
					mapper.close();
				} catch (IOException closeException) {
					e.addSuppressed(closeException);
				}
			}
			try {
				this.data.close();
			} catch (IOException closeException) {
				e.addSuppressed(closeException);
			}
			throw e;
		}
	}

	/**
	 * Create an Etch instance using a temporary file.
	 * @return The new Etch instance
	 * @throws IOException If an IO error occurs
	 */
	public static Etch createTempEtch() throws IOException {
		Etch newEtch =  createTempEtch("etch-"+tempIndex);
		tempIndex++;
		return newEtch;
	}

	/**
	 * Create an Etch instance using a temporary file and compiled configuration.
	 * @param config compiled Etch configuration
	 * @return The new Etch instance
	 * @throws IOException If an IO error occurs
	 */
	public static Etch createTempEtch(EtchConfig config) throws IOException {
		Etch newEtch=createTempEtch("etch-"+tempIndex,config);
		tempIndex++;
		return newEtch;
	}

	/**
	 * Create an Etch instance using a temporary file with a specific file prefix.
	 * @param prefix temporary file prefix to use
	 * @return The new Etch instance
	 * @throws IOException If an IO error occurs
	 */
	public static Etch createTempEtch(String prefix) throws IOException {
		File data = File.createTempFile(prefix+"-", null);
		if (Constants.ETCH_DELETE_TEMP_ON_EXIT) data.deleteOnExit();
		return new Etch(data);
	}

	/**
	 * Create an Etch instance using a temporary file and compiled configuration.
	 * @param prefix temporary file prefix to use
	 * @param config compiled Etch configuration
	 * @return The new Etch instance
	 * @throws IOException If an IO error occurs
	 */
	public static Etch createTempEtch(String prefix, EtchConfig config) throws IOException {
		if (config==null) throw new IllegalArgumentException("Etch config cannot be null");
		File data = File.createTempFile(prefix+"-", null);
		if (Constants.ETCH_DELETE_TEMP_ON_EXIT) data.deleteOnExit();
		return new Etch(data,config);
	}

	/**
	 * Create an Etch instance using the specified file
	 * @param file File with which to create Etch instance
	 * @return The new Etch instance
	 * @throws IOException If an IO error occurs
	 */
	public static Etch create(File file) throws IOException {
		return new Etch(file);
	}

	/**
	 * Create or open an Etch instance using compiled configuration. For an
	 * existing file, the configured version must match the file header.
	 *
	 * @param file File with which to create the Etch instance
	 * @param config compiled Etch configuration
	 * @return Etch instance
	 * @throws IOException If an IO error occurs or the file version conflicts
	 */
	public static Etch create(File file, EtchConfig config) throws IOException {
		if (config==null) throw new IllegalArgumentException("Etch config cannot be null");
		return new Etch(file,config);
	}

	static EtchConfig resolveExistingConfig(EtchHeader resolvedHeader,
			EtchConfig requestedConfig, File dataFile) throws IOException {
		short fileVersion=resolvedHeader.version();
		if ((requestedConfig!=null)&&(requestedConfig.getVersion()!=fileVersion)) {
			throw new IOException("Configured Etch version "+requestedConfig.getVersion()
					+" does not match file version "+fileVersion+": "+dataFile);
		}
		if (!(resolvedHeader instanceof EtchV3Header v3Header)) {
			return (requestedConfig==null)?EtchConfig.create(fileVersion):requestedConfig;
		}

		EtchConfig.CipherMode fileCipher;
		try {
			fileCipher=EtchConfig.CipherMode.fromFileId(v3Header.cipherId());
		} catch (IllegalArgumentException e) {
			throw new IOException("Unsupported Etch v3 file cipher: "+v3Header.cipherId(),e);
		}
		EtchConfig basis=(requestedConfig==null)?EtchConfig.create(fileVersion):requestedConfig;
		if ((requestedConfig!=null)&&(requestedConfig.getCipherMode()!=fileCipher)) {
			throw new IOException("Configured Etch cipher "+requestedConfig.getCipherMode().configName()
					+" does not match file cipher "+fileCipher.configName()+": "+dataFile);
		}
		if ((requestedConfig!=null)
				&&(requestedConfig.isIndexEncrypted()!=v3Header.isIndexEncrypted())) {
			throw new IOException("Configured Etch index encryption does not match file: "+dataFile);
		}
		AccountKey configuredHint=basis.getPublicKeyHint();
		AccountKey fileHint=v3Header.publicKeyHint();
		if ((configuredHint!=null)&&!configuredHint.equals(fileHint)) {
			throw new IOException("Configured Etch public-key hint does not match file: "+dataFile);
		}
		return basis.withV3FileOptions(fileCipher,v3Header.isIndexEncrypted(),fileHint);
	}

	static EtchFileCipher createFileCipher(EtchHeader resolvedHeader,
			EtchConfig effectiveConfig) throws IOException {
		if (!(resolvedHeader instanceof EtchV3Header v3Header)) return null;
		byte[] cipherSecret=effectiveConfig.encryptionSecret();
		try {
			return switch (v3Header.cipherId()) {
				case V3_CIPHER_NONE -> null;
				case V3_CIPHER_AES_256_CTR -> AES256CTREtchCipher.derive(cipherSecret,v3Header.fileSalt());
				case V3_CIPHER_CHACHA20 -> ChaCha20EtchCipher.derive(cipherSecret,v3Header.fileSalt());
				default -> throw new IOException("Unsupported Etch v3 file cipher: "+v3Header.cipherId());
			};
		} finally {
			if (cipherSecret!=null) Arrays.fill(cipherSecret,(byte)0);
		}
	}

	private void readData(long position, byte[] destination, int offset, int length)
			throws IOException {
		fileAccess.readData(rawPointer(position),destination,offset,length);
	}

	private void writeData(long position, byte[] source, int offset, int length)
			throws IOException {
		fileAccess.writeData(rawPointer(position),source,offset,length);
	}

	/**
	 * Writes a key / value pair to the immutable store.
	 *
	 * CONCURRENCY: Hold a lock for a single writer
	 *
	 * @param key A key value (typically the Hash)
	 * @param value Value data to associate with the key
	 * @return Ref after writing to store
	 * @throws IOException If an IO error occurs
	 */
	public synchronized <T extends ACell > Ref<T> write(AArrayBlob key, Ref<T> value) throws IOException {
		prepareMutation();
		return write(key,0,value,indexStart);
	}

	private void prepareMutation() throws IOException {
		if (!requiresOpenTransition) return;
		header.prepareMutation(fileAccess);
		requiresOpenTransition=false;
	}

	private <T extends ACell > Ref<T> write(AArrayBlob key, int level, Ref<T> ref, long indexPosition) throws IOException {
		if (level>=MAX_LEVEL) {
			throw new Error("Max Level exceeded for key: "+key);
		}

		int isize=indexSize(level);
		int mask=isize-1;
		final int digit=getDigit(key,level);
		
		long slotValue=readSlot(indexPosition,digit);
		long type=slotType(slotValue);

		if (slotValue==0L) {
			// empty location, so simply write new value
			return writeNewData(indexPosition,digit,key,ref,POINTER_PLAIN);

		} else if (type==POINTER_INDEX) {
			// recursively check next level of index
			long newIndexPosition=rawPointer(slotValue); // clear high bits
			return write(key,level+1,ref,newIndexPosition);

		} else if (type==POINTER_PLAIN) {
			// existing data pointer (non-zero)
			// check if we have the same value first, otherwise need to resolve conflict
			// This should have the current (potential collision) key in tempArray
			if (checkMatchingKey(key,slotValue)) {
				return updateInPlace(slotValue,ref);
			}

			// we need to check the next slot position to see if we can extend to a chain
			int nextDigit=(digit+1)%isize;
			long nextSlotValue=readSlot(indexPosition,nextDigit);

			// if next slot is empty, we can make a chain!
			if (buildChains&&(nextSlotValue==0L)) {
				// update current slot to be the start of a chain
				writeSlot(indexPosition,digit,slotValue|POINTER_START);

				// write new data pointer to next slot
				long newDataPointer=appendData(key,ref);
				writeSlot(indexPosition,nextDigit,newDataPointer|POINTER_CHAIN);

				return ref;
			}
			
			// have collision, so create new index node including the existing pointer
			int nextLevel=level+1;
			// Note: temp should contain key from checkMatchingKey!
			byte[] temp=tempArray.get();
			int nextDigitOfCollided=getDigit(Blob.wrap(temp,0,KEY_SIZE),nextLevel);
			long newIndexPosition=appendLeafIndex(nextLevel,nextDigitOfCollided,slotValue);

			// put index pointer into this index block, setting flags for index node
			writeSlot(indexPosition,digit,newIndexPosition|POINTER_INDEX);

			// recursively write this key
			return write(key,nextLevel,ref,newIndexPosition);
		} else if (type==POINTER_START) {
			// first check if the start pointer is the right value. if so, just update in place
			if (checkMatchingKey(key, slotValue)) {
				return updateInPlace(slotValue,ref);
			}

			// now scan slots, looking for either the right value or an empty space
			int i=1;
			while (i<isize) {
				int ix=(digit+i)&mask;
				slotValue=readSlot(indexPosition,ix);

				// If we reach an empty location, write a chain continuation.
				if (slotValue==0L) {
					return writeNewData(indexPosition,ix,key,ref,POINTER_CHAIN);
				}

				// if we are not in a chain, we have reached the maximum chain length. Exit loop and compress.
				if (slotType(slotValue)!=POINTER_CHAIN) break;

				// if we found the key itself, return since already stored.
				if (checkMatchingKey(key, slotValue)) {
					return updateInPlace(slotValue,ref);
				}

				i++;
			}

			// we now need to collapse the chain to next level, since it cannot be extended.
			int nextLevel=level+1;
			// System.out.println("Compressing chain, offset="+keyOffset+" chain length="+i+" with key "+key+ " indexDat= "+readBlob(indexPosition,2048));

			// first we build a new next level index block, containing our new data
			long newDataPointer=appendData(key,ref);
			int nextDigit=getDigit(key,nextLevel);
			long newIndexPos=appendLeafIndex(nextLevel,nextDigit,newDataPointer);

			// for each element in chain, rewrite existing data to new index block. i is the length of chain
			for (int j=0; j<i; j++) {
				int movingDigit=(digit+j)&mask;
				long movingSlotValue=readSlot(indexPosition,movingDigit);
				long dp=rawPointer(movingSlotValue); // just the raw pointer
				rewriteExistingData(newIndexPos,nextLevel,dp);
			}

			// publish the complete new index block BEFORE clearing the old chain:
			// lock-free readers then see either the intact chain or the new block
			writeSlot(indexPosition,digit,newIndexPos|POINTER_INDEX);
			for (int j=1; j<i; j++) {
				writeSlot(indexPosition,(digit+j)&mask,0L); // clear the old chain
			}
			return ref;
		} else if (type==POINTER_CHAIN) {
			// need to collapse existing chain
			int chainStartDigit=seekChainStart(indexPosition,digit,isize);
			if (chainStartDigit==digit) throw new Error("Can't start chain at this digit? "+digit);
			int chainEndDigit=seekChainEnd(indexPosition,digit,isize);
			int nextLevel=level+1;

			int n=(chainStartDigit==chainEndDigit)?isize:(chainEndDigit-chainStartDigit)&mask;
			long newIndexPos=appendNewIndexBlock(nextLevel);
			for (int j=0; j<n; j++) {
				int movingDigit=(chainStartDigit+j)&mask;
				long movingSlotValue=readSlot(indexPosition,movingDigit);
				long dp=rawPointer(movingSlotValue); // just the raw pointer
				rewriteExistingData(newIndexPos,nextLevel,dp);
			}

			// publish the complete new index block BEFORE clearing the old chain:
			// lock-free readers then see either the intact chain or the new block
			writeSlot(indexPosition,chainStartDigit,newIndexPos|POINTER_INDEX);
			for (int j=1; j<n; j++) {
				writeSlot(indexPosition,(chainStartDigit+j)&mask,0L); // clear the old chain
			}

			// write to the current slot
			return writeNewData(indexPosition,digit,key,ref,POINTER_PLAIN);
		} else {
			throw new Error("Unexpected type: "+type);
		}
	}


	/**
	 * Finds the start digit of a chain, stepping backwards from the given digit
	 * @param indexPosition Position of index block
	 * @param digit Position at which a chain continuation is detected, i.e. search begins.
	 * @return
	 * @throws IOException
	 */
	private int seekChainStart(long indexPosition, int digit, int isize) throws IOException {
		int mask=isize-1;
		digit=digit&mask;
		int i=(digit-1)&mask;
		while (i!=digit) {
			long slotValue=readSlot(indexPosition,i);
			if (slotType(slotValue)==POINTER_START) return i;
			i=(i-1)&mask;
		}
		throw new Error("Infinite chain?");
	}

	/**
	 * Finds the end digit of a chain, stepping forwards from the given digit
	 * @param indexPosition
	 * @param digit
	 * @return Next index position that is not a chain continuation
	 * @throws IOException
	 */
	private int seekChainEnd(long indexPosition, int digit, int isize) throws IOException {
		int mask=isize-1;
		digit=digit&mask;
		int i=(digit+1)&mask;
		while (i!=digit) {
			long slotValue=readSlot(indexPosition,i);
			if (slotType(slotValue)!=POINTER_CHAIN) return i;
			i=(i+1)&mask;
		}
		throw new Error("Infinite chain?");
	}

	/**
	 * Writes and existing data pointer into an index block. Existing data assumed to be unique,
	 * so we don't check for key clashes.
	 *
	 * We also don't do chaining, assume clashes unlikely, and that the block given has
	 * no existing chains. This is because the only time this gets called is when unwinding an 
	 * existing chain.
	 *
	 * @param indexPosition Position of index Block
	 * @param level Level in Etch database
	 * @param dp Raw data pointer
	 * @throws IOException
	 */
	private void rewriteExistingData(long indexPosition, int level, long dp) throws IOException {
		rewriteExistingData(indexPosition,level,dp,readValueKey(dp));
	}

	private void rewriteExistingData(long indexPosition, int level, long dp, AArrayBlob key) throws IOException {
		// index into existing key data to get current digit
		int digit=getDigit(key,level);

		long currentSlot=readSlot(indexPosition,digit);
		long type = currentSlot&POINTER_TYPE_MASK;
		if (currentSlot==0L) {
			writeSlot(indexPosition,digit,dp);
		} else if (type==POINTER_INDEX) {
			// Write into the new index block (presumably recently created)
			rewriteExistingData(rawPointer(currentSlot),level+1,dp,key);
		} else if (type==POINTER_PLAIN) {
			int newLevel=level+1;

			// expand to a new index block for collision
			long newIndexPosition=appendNewIndexBlock(newLevel);
			rewriteExistingData(newIndexPosition,newLevel,currentSlot);
			rewriteExistingData(newIndexPosition,newLevel,dp,key);
			writeSlot(indexPosition,digit,newIndexPosition|POINTER_INDEX);
		} else {
			throw new Error("Unexpected type while rewriting existing data: "+type);
		}
	}

	/**
	 * Reads a blob of the specified length from storage
	 * @param pointer
	 * @param length
	 * @return
	 * @throws IOException
	 */
	Blob readBlob(long pointer, int length) throws IOException {
		byte[] bs=new byte[length];
		readData(pointer,bs,0,length);
		return Blob.wrap(bs);
	}
	
	public Hash readValueKey(long ptr) throws IOException {
		byte[] bs=new byte[KEY_SIZE];
		readData(ptr,bs,0,KEY_SIZE);
		return Hash.wrap(bs);
	}


	/**
	 * Gets the type of a slot, given the slot value
	 * @param slotValue
	 * @return
	 */
	private long slotType(long slotValue) {
		return slotValue&POINTER_TYPE_MASK;
	}

	/**
	 * Utility function to truncate file. Won't work if mapped byte buffers are active?
	 * @throws FileNotFoundException
	 * @throws IOException
	 */
	protected void truncateFile() throws FileNotFoundException, IOException {
		try (FileOutputStream fos=new FileOutputStream(file, true)) {
			FileChannel outChan = fos.getChannel() ;
			outChan.truncate(fileAccess.getDataLength());
		}
	}

	/**
	 * Close all files resources with this Etch store, including writing the final
	 * data length.
	 */
	void close() {
		if (!(data.getChannel().isOpen())) return; // already closed
		synchronized(this) {
			try {
				header.close(fileAccess);
				
				fileAccess.close();
	
				data.close();
	
			} catch (IOException e) {
				// ignore
			}
		}
	}


	/**
	 * @return Current data size in bytes
	 */
	public long getDataLength() {
		return fileAccess.getDataLength();
	}

	/**
	 * Writes the data length field for the Etch file. S
	 * @throws IOException
	 */
	protected void writeDataLength() throws IOException {
		header.writeDataLength(fileAccess);
	}
	
	/**
	 * Gets the Etch version associated with this file
	 * @return Return Etch version number
	 */
	public short getVersion() {
		return version;
	}

	/**
	 * Gets the compiled configuration used to construct this Etch instance.
	 */
	public EtchConfig getConfig() {
		return config;
	}

	long getIndexStart() {
		return indexStart;
	}

	/**
	 * Gets the active file-mapping implementation name for diagnostics and
	 * performance reporting.
	 *
	 * @return mapper implementation name
	 */
	public String getMappingImplementation() {
		return fileAccess.implementationName();
	}

	/**
	 * Gets the raw pointer for, given the slot value (clears high bits)
	 * @param slotValue Value in slot
	 * @return Pointer extracted from slot value
	 */
	public long rawPointer(long slotValue) {
		return slotValue&~POINTER_TYPE_MASK;
	}

	/**
	 * Checks if the key matches the data at the specified data pointer position
	 * @param key
	 * @param dataPointer Pointer to data. Type bits in MSBs will be ignored.
	 * @return true if key matches at given data position
	 * @throws IOException
	 */
	private boolean checkMatchingKey(AArrayBlob key, long dataPointer) throws IOException {
		long dataPosition=rawPointer(dataPointer);
		byte[] temp=tempArray.get();
		readData(dataPosition,temp,0,KEY_SIZE);
		if (key.equalsBytes(temp,0)) {
			// key already in store matching at this data position
			return true;
		}
		return false;
	}

	/**
	 * Appends a leaf index block including exactly one data pointer, at the specified digit position.
	 * WARNING: Overwrites temp array!
	 * @param digit Digit position for the data pointer to be stored at (0..255, high bits ignored)
	 * @param dataPointer Single data pointer to include in new index block
	 * @return the position of the new index block
	 * @throws IOException
	 */
	private long appendLeafIndex(int level, int digit, long dataPointer) throws IOException {
		assert(level>0);
		int isize=indexSize(level);
		int mask=isize-1;
		int indexBlockLength=POINTER_SIZE*isize;
		digit=digit&mask;
		
		byte[] temp=tempArray.get();
		Arrays.fill(temp, 0,indexBlockLength,(byte)0x00);
		
		int ix=POINTER_SIZE*digit; // compute position in block. note: should be already masked above
		Utils.writeLong(temp, ix,dataPointer); // single node
		return fileAccess.appendIndex(temp,0,indexBlockLength,POINTER_SIZE);
	}

	/**
	 * Reads a Blob from the database, returning null if not found
	 * @param key Key to read from Store
	 * @return Blob containing the data, or null if not found
	 * @throws IOException If an IO error occurs
	 */
	public <T extends ACell> RefSoft<T> read(AArrayBlob key) throws IOException {
		Counters.etchRead++;
		RefSoft<T> result=readAtIndex(key,0,indexStart);
		if (result==null) {
			Counters.etchMiss++;
		}
		return result;
	}
	
	/**
	 * Reads a Cell from the specified location in an Etch file. WARNING: does not perform any validation
	 * @param <T> Type of Cell expected
	 * @param ptr Pointer offset into Etch file. Type flags are ignored.
	 * @return Cell value (may be null)
	 * @throws IOException In event of IO Error
	 */
	@SuppressWarnings("unchecked")
	public <T extends ACell> T readCell(long ptr) throws IOException {
		ptr=rawPointer(ptr);
		return (T)(read(null,ptr).getValue());
	}
		
	public <T extends ACell> RefSoft<T> read(AArrayBlob key,long pointer) throws IOException {
		long recordPosition=rawPointer(pointer);
		EtchFileAccess.DataRecord stored;
		if (key==null) {
			stored=fileAccess.readDataRecord(recordPosition,true);
		} else if (fileAccess.isEncrypted()) {
			stored=fileAccess.readDataRecord(recordPosition,key);
		} else {
			// The plaintext index traversal has already checked the stored key.
			stored=fileAccess.readDataRecord(recordPosition,false);
		}
		if (stored==null) return null;
		byte[] recordHeader=stored.header();
		int headerOffset=stored.headerOffset();
		if (key==null) key=Hash.wrap(Arrays.copyOf(recordHeader,KEY_SIZE));
		return decodeDataRecord(key,pointer,stored,recordHeader,headerOffset);
	}

	private <T extends ACell> RefSoft<T> decodeDataRecord(AArrayBlob key, long pointer,
			EtchFileAccess.DataRecord stored, byte[] recordHeader, int headerOffset) {
		
		// get flags byte
		byte flagByte=recordHeader[headerOffset];

		// Get memory size
		long memorySize=Utils.readLong(recordHeader,headerOffset+Byte.BYTES,Long.BYTES);

		// get Data length
		int length=stored.encoding().length;
		Blob encoding=Blob.wrap(stored.encoding());
		try {
			Hash hash=Hash.wrap(key);
			T cell=store.decode(encoding);
			encoding.attachContentHash(hash);

			if (memorySize>0) {
				// need to attach memory size for cell
				cell.attachMemorySize(memorySize);
			}

			RefSoft<T> ref=RefSoft.create(store,cell, (int)flagByte);
			cell.attachRef(ref);

			return ref;
		} catch (BadFormatException e) {
			throw new Error("Failed to read data in etch store: "+encoding.toHexString()+" flags = "+Utils.toHexString(flagByte)+" length ="+length+" pointer = "+Utils.toHexString(pointer)+ " memorySize="+memorySize,e);
		}
	}

	/**
	 * Flushes any changes to persistent storage.
	 * @throws IOException If an IO error occurs
	 */
	public synchronized void flush() throws IOException {
		header.writeDataLength(fileAccess);
		header.sync(fileAccess);
	}

	/**
	 * Gets the slot value at the specified digit position in an index block. Doesn't affect temp array.
	 * @param indexPosition Position of index block
	 * @param digit Position of slot within index block
	 * @return Pointer value (including type bits in MSBs)
	 * @throws IOException In case of IO Error
	 */
	public long readSlot(long indexPosition, int digit) throws IOException {
		long pointerIndex=indexPosition+POINTER_SIZE*digit;
		return fileAccess.readIndexSlotAcquire(pointerIndex);
	}

	/**
	 * Creates and writes a new data pointer at the specified position, storing the key/value
	 * and applying the specified type to the pointer stored in the slot
	 *
	 * @param position Position to write the data pointer
	 * @param key Key for the data
	 * @param value Value of the data
	 * @return
	 * @throws IOException
	 */
	private <T extends ACell > Ref<T> writeNewData(long indexPosition, int digit, AArrayBlob key, Ref<T> value, long type) throws IOException {
		long newDataPointer=appendData(key,value)|type;
		writeSlot(indexPosition, digit, newDataPointer);
		return value;
	}

    /**
     * Updates a Ref in place at the specified position. Assumes data not changed.
     * @param position Slot value containing position in storage file
     * @param ref
     * @return
     * @throws IOException
     */
	private <T extends ACell > Ref<T> updateInPlace(long position, Ref<T> ref) throws IOException {
		// ensure we have a raw position
		position=rawPointer(position);
		
		long labelPosition=position+KEY_SIZE;

		// Get current stored values
		byte[] label=tempArray.get();
		readData(labelPosition,label,0,LABEL_SIZE);
		int currentFlags=label[0];
		int newFlags=Ref.mergeFlags(currentFlags,ref.getFlags()); // idempotent flag merge

		long currentSize=Utils.readLong(label,Byte.BYTES,Long.BYTES);

		if (currentFlags==newFlags) return ref;

		// We have a status change, need to increase status of store
		label[0]=(byte)newFlags;

		// maybe update size, if not already persisted
		if ((currentSize==0L)&&((newFlags&Ref.STATUS_MASK)>=Ref.PERSISTED)) {
			Utils.writeLong(label,Byte.BYTES,ref.getValue().getMemorySize());
		}
		writeData(labelPosition,label,0,LABEL_SIZE);

		return ref.withFlags(newFlags);	// reflect merged flags
	}

	/**
	 * Writes a slot value to an index block.
	 *
	 * @param indexPosition
	 * @param digit Digit radix position in index block
	 * @param slotValue
	 * @throws IOException
	 */
	private void writeSlot(long indexPosition, int digit, long slotValue) throws IOException {
		long position=indexPosition+digit*POINTER_SIZE;
		fileAccess.writeIndexSlotRelease(position,slotValue);
	}

	private <T extends ACell> RefSoft<T> readMatching(AArrayBlob key, long pointer)
			throws IOException {
		if (!fileAccess.isEncrypted()&&!checkMatchingKey(key,pointer)) return null;
		return read(key,pointer);
	}
	
	/**
	 * Visits all index blocks in this Etch file.
	 *
	 * WARNING: inherently racy under concurrent writes. Writes restructure the
	 * index in place (chain collapses, slot repointing, new index blocks), so a
	 * concurrent visit may miss entries or visit them twice. Only use on a store
	 * that is not undergoing any writes.
	 *
	 * @param v Visitor to apply to each index block
	 * @throws IOException in case of IO error
	 */
	public void visitIndex(IEtchIndexVisitor v) throws IOException {
		int[] bs=new int[32];
		visitIndex(v,bs,0,indexStart);
	}

	private void visitIndex(IEtchIndexVisitor v, int[] digits, int level, long indexPointer) throws IOException {
		v.visit(this, level, digits, indexPointer);
		int n=indexSize(level);
		for (int i=0; i<n; i++) {
			long slot=readSlot(indexPointer,i);
			if ((slot&POINTER_TYPE_MASK)==POINTER_INDEX) {
				digits[level]=i;
				visitIndex(v,digits,level+1,rawPointer(slot));
			}
		}
	}

	/**
	 * Finds and reads a data block from the given offset into the key.
	 * @param key Key value
	 * @param level Level in Etch index (0 = top level)
	 * @param indexPosition offset of the current index block
	 * @return decoded reference, or {@code null} if not found
	 * @throws IOException
	 */
	private <T extends ACell> RefSoft<T> readAtIndex(AArrayBlob key, int level,
			long indexPosition) throws IOException {
		if (level>=MAX_LEVEL) {
			throw new Error("Etch index level exceeded for key: "+key);
		}
		int isize=indexSize(level);
		int mask=isize-1;
		int digit=getDigit(key,level);
		long slotValue=readSlot(indexPosition,digit);
		
		long type=(slotValue&POINTER_TYPE_MASK);
		if (slotValue==0) {
			// Empty slot i.e. not found
			return null;
		} else if (type==POINTER_INDEX) {
			// recursively check next index node
			long newIndexPosition=rawPointer(slotValue);
			return readAtIndex(key,level+1,newIndexPosition);
		} else if (type==POINTER_PLAIN) {
			return readMatching(key,slotValue);
		} else if (type==POINTER_CHAIN) {
			// continuation of chain from some previous index, therefore key can't be present
			return null;
		} else if (type==POINTER_START) {
			// Optimistic lock-free chain scan. A concurrent collapse publishes its
			// new index block into the start slot BEFORE clearing chain entries, so
			// on a miss we revalidate the start slot and retry if it changed. Slot
			// transitions are one-way (PLAIN -> START -> INDEX), bounding retries.
			long startValue=slotValue;
			int i=0;
			while (i<isize) {
				long ptr=slotValue&(~POINTER_TYPE_MASK);
				RefSoft<T> result=readMatching(key,ptr);
				if (result!=null) return result;

				i++; // advance to next position
				slotValue=readSlot(indexPosition,(digit+i)&mask);
				type=(slotValue&POINTER_TYPE_MASK);
				if (!(type==POINTER_CHAIN)) break; // reached end of chain
			}
			if (readSlot(indexPosition,digit)!=startValue) {
				// chain restructured during our scan: retry at this position
				return readAtIndex(key,level,indexPosition);
			}
			return null;
		} else {
			throw new Error("Shouldn't be possible!");
		}
	}

	/**
	 * Gets the radix index digit for the specified level
	 * @param key Blob key for index
	 * @param level Level of Etch store index to get digit for
	 * @return
	 */
	private int getDigit(AArrayBlob key, int level) {
		if (level==0) return key.shortAt(0)&0xffff;
		if (level==1) return key.byteAt(2)&0xFF;
		int bi=(level+4)/2;      // level 2,3 maps to 3 etc.
		boolean hi=(level&1)==0; // we want high byte if even
		byte v= key.byteAt(bi);
		return (hi?(v>>4):v)&0xf;
	}
	
	/**
	 * Gets the index block size for a given level
	 * @param level Level of index block in Etch store
	 * @return Index block size as number of entries
	 */
	public int indexSize(int level) {
		if (level==0) return ROOT_INDEX_SIZE;
		if (level==1) return SECOND_LEVEL_INDEX_SIZE;
		return DEEP_INDEX_SIZE;
	}

	/**
	 * Append a new index block to the store file. The new Index block will be initially empty,
	 * i.e. filled completely with zeros.
	 * WARNING: Overwrites temp array!
	 * @return The location of the newly added index block.
	 * @throws IOException
	 */
	private long appendNewIndexBlock(int level) throws IOException {
		if (level>=MAX_LEVEL) {
			// Invalid level! Prepare to output error
			throw new Error("Overflowing key size - key collision?");
		}
		
		int isize=indexSize(level);
		int sizeBytes=isize*POINTER_SIZE;
		
		// The v1 root deliberately starts at byte 44; all child indexes are aligned.
		int alignment=(level==0)?1:POINTER_SIZE;
		long position=fileAccess.appendZeroIndex(sizeBytes,alignment);
		if ((level==0)&&(position!=indexStart)) {
			throw new IllegalStateException("Unexpected Etch root index position: "+position);
		}
		return position;
	}

	/**
	 * Appends a new key / value data block. Returns a pointer to the data with cleared type bits.
	 *
	 * @param key The key to include in the data block
	 * @param a the Blob representing the new data value
	 * @return The position of the new data block
	 * @throws IOException
	 */
	private long appendData(AArrayBlob key,Ref<?> ref) throws IOException {
		assert(key.count()==KEY_SIZE);
		Counters.etchWrite++;

		// Get relevant values for writing
		// probably need to call these first, might move mbb position?
		ACell cell=ref.getValue();
		Blob encoding=cell.getEncoding();
		int status=ref.getStatus();

		long memorySize=0L;
		if (status>=Ref.PERSISTED) {
			memorySize=cell.getMemorySize();
		}

		short length=Utils.checkedShort(encoding.count());
		if (length==0) {
			// Blob b=cell.createEncoding();
			throw new Error("Etch trying to write zero length encoding for: "+Utils.getClassName(cell));
		}

		byte[] recordHeader=tempArray.get();
		int flags=ref.flagsWithStatus(Math.max(ref.getStatus(),Ref.STORED));
		recordHeader[0]=(byte)flags; // currently all flags fit in one byte
		Utils.writeLong(recordHeader,Byte.BYTES,memorySize);
		Utils.writeShort(recordHeader,LABEL_SIZE,length);

		return fileAccess.appendDataRecord(key,recordHeader,
				LABEL_SIZE+ENCODING_LENGTH_SIZE,encoding);
	}

	public File getFile() {
		return file;
	}

	public String getFileName() {
		return fileName;
	}

	public synchronized Hash getRootHash() throws IOException {
		return header.getRootHash(fileAccess);
	}

	/**
	 * Writes the root data hash to the Store
	 * @param h Hash value to write
	 * @throws IOException If IO Error occurs
	 */
	public synchronized void setRootHash(Hash h) throws IOException {
		prepareMutation();
		header.setRootHash(fileAccess,h);
	}

	public void setStore(EtchStore etchStore) {
		this.store=etchStore;
	}

	/**
	 * Gets the type code for an index slot value
	 * @param slot Raw slot value
	 * @return Type code
	 */
	public long extractType(long slot) {
		return slot&POINTER_TYPE_MASK;
	}




}
