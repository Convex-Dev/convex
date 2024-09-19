package convex.etch;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.Constants;
import convex.core.data.AArrayBlob;
import convex.core.data.ACell;
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
 * Header of file is 42 bytes as follows:
 * - Magic number 0xe7c6 (2 bytes)
 * - Database length in bytes (8 bytes)
 * - Root hash (32 bytes)
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
	// structural constants for data block
	static final int KEY_SIZE=32;
	static final int MAX_LEVEL=60; // 2 bytes + 1 byte + 58 hex digits for 29 remaining bytes

	static final int LABEL_SIZE=1+8; // Flags (byte) plus Memory Size (long)
	static final int LENGTH_SIZE=2;
	static final int POINTER_SIZE=8;

	// constants for memory mapping buffers into manageable regions
	static final long MAX_REGION_SIZE=1<<30; // 1GB seems reasonable, note JVM 2GB limit :-/
	static final long REGION_MARGIN=65536; // 64k margin for writes past end of current buffer

	/**
	 * Magic number for Etch files, must be first 2 bytes
	 */
	static final byte[] MAGIC_NUMBER=Utils.hexToBytes("e7c6");
	
	/**
	 * Version number
	 */
	static final short ETCH_VERSION=1;

	static final int SIZE_HEADER_MAGIC=2;
	static final int SIZE_HEADER_VERSION=2;
	static final int SIZE_HEADER_FILESIZE=8;
	static final int SIZE_HEADER_ROOT=32;
	
	static final int ZLEN=16384;
	static final byte[] ZERO_ARRAY=new byte[ZLEN];

	/**
	 * Length of Etch header. Used to be 42 until we added the version number
	 *
	 * "The Ultimate Answer to Life, The Universe and Everything is... 42!"
     * - Douglas Adams, The Hitchhiker's Guide to the Galaxy
	 */
	static final int SIZE_HEADER=SIZE_HEADER_MAGIC+SIZE_HEADER_VERSION+SIZE_HEADER_FILESIZE+SIZE_HEADER_ROOT;

	protected static final long OFFSET_VERSION = SIZE_HEADER_MAGIC; // Skip past magic number
	protected static final long OFFSET_FILE_SIZE = OFFSET_VERSION+SIZE_HEADER_VERSION; // Skip past version
	protected static final long OFFSET_ROOT_HASH = OFFSET_FILE_SIZE+SIZE_HEADER_FILESIZE; // Skip past file size

	/**
	 * Start position of first index block
	 * This is immediately after a long data length pointer at the start of the file
	 */
	static final long INDEX_START=SIZE_HEADER;

	static final long TYPE_MASK = 0xC000000000000000L;
	static final long PTR_PLAIN = 0x0000000000000000L; // direct pointer to data
	static final long PTR_INDEX = 0x4000000000000000L; // pointer to index block
	static final long PTR_START = 0x8000000000000000L; // start of chained entries
	static final long PTR_CHAIN = 0xC000000000000000L; // chained entries after start

	private static final Logger log=LoggerFactory.getLogger(Etch.class.getName());

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

	/**
	 * List of MappedByteBuffers for each region of the database file.
	 */
	private final ArrayList<MappedByteBuffer> regionMap=new ArrayList<>();

	private long dataLength=0;

	private boolean BUILD_CHAINS=true;
	private EtchStore store;

	private Etch(File dataFile) throws IOException {
		// Ensure we have a RandomAccessFile that exists
		this.file=dataFile;
		if (!dataFile.exists()) dataFile.createNewFile();
		this.data=new RandomAccessFile(dataFile,"rw");

		this.fileName = dataFile.getName();

		// Try to exclusively lock the Etch database file
		FileChannel fileChannel=this.data.getChannel();
		FileLock lock=fileChannel.tryLock();
		if (lock==null) {
			log.error("Unable to obtain lock on file: {}",dataFile);
			throw new IOException("File lock failed");
		}

		// at this point, we have an exclusive lock on the database file.

		if (dataFile.length()==0) {
			// Need to populate  new file, with data length long and initial index block
			MappedByteBuffer mbb=seekMap(0);

			// write Header, initally zeros expect magic number and version
			byte[] temp=new byte[SIZE_HEADER];
			System.arraycopy(MAGIC_NUMBER, 0, temp, 0, SIZE_HEADER_MAGIC);
			Utils.writeShort(temp, (int)OFFSET_VERSION, ETCH_VERSION);
			mbb.put(temp);
			
			dataLength=SIZE_HEADER; // advance past initial long

			// add an index block
			mbb=seekMap(SIZE_HEADER);
			long indexStart=appendNewIndexBlock(0);
			assert(indexStart==INDEX_START);

			// ensure data length is initially correct
			writeDataLength();
		} else {
			// existing file, so need to read the length pointer
			MappedByteBuffer mbb=seekMap(0);
			byte[] check=new byte[2];
			mbb.get(check);
			if(!Arrays.equals(MAGIC_NUMBER, check)) {
				throw new IOException("Bad magic number! Probably not an Etch file: "+dataFile);
			}
			short version=mbb.getShort();
			if (version!=ETCH_VERSION) throw new IOException("Bad Etch version: expected "+ETCH_VERSION+" but was "+version+ " in "+dataFile);

			long length = mbb.getLong();
			dataLength=length;
		}

		// shutdown hook to close file / release lock
		convex.core.util.Shutdown.addHook(Shutdown.ETCH,this::close);
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
	 * Create an Etch instance using the specified file
	 * @param file File with which to create Etch instance
	 * @return The new Etch instance
	 * @throws IOException If an IO error occurs
	 */
	public static Etch create(File file) throws IOException {
		Etch etch= new Etch(file);
		log.debug("Etch created on file: {} with data length: {}", file, etch.dataLength);
		return etch;
	}

	/**
	 * Gets a MappedByteBuffer for a given position, seeking to the specified location.
	 * Type flags are ignored if included in the position pointer.
	 *
	 * @param position Target position for the MappedByteBuffer
	 * @return MappedByteBuffer instance with correct position.
	 * @throws IOException
	 */
	private MappedByteBuffer seekMap(long position) throws IOException {
		position=rawPointer(position); // ensure we don't have any pesky type bits

		if ((position<0)||(position>dataLength)) {
			throw new EtchCorruptionError("Seek out of range in Etch file: position="+Utils.toHexString(position)+ " dataLength="+Utils.toHexString(dataLength)+" file="+file.getName());
		}

		MappedByteBuffer mbb=(MappedByteBuffer)((ByteBuffer)getInternalBuffer(position)).duplicate();

		mbb.position(Utils.checkedInt(position%MAX_REGION_SIZE));
		return mbb;
	}

	/**
	 * Gets the internal mapped byte buffer for the specified region of the Etch database
	 * 
	 * @param position Position for which to get buffer
	 * @return Mapped Byte Buffer for specified region
	 * @throws IOException
	 */
	private MappedByteBuffer getInternalBuffer(long position) throws IOException {
		int regionIndex=Utils.checkedInt(position/MAX_REGION_SIZE); // 1GB chunks

		// Get current mapped region, or null if out of range
		int regionMapSize=regionMap.size();
		MappedByteBuffer mbb=(regionIndex<regionMapSize)?regionMap.get(regionIndex):null;

		// Call createBuffer if mapped region does not exist, or is too small
		if ((mbb==null)||((mbb.capacity()+regionIndex*MAX_REGION_SIZE)<position+REGION_MARGIN)) {
			mbb=createBuffer(regionIndex);
		}

		return mbb;
	}

	/**
	 * Create a MappedByteBuffer at the specified region index position.
	 *
	 * CONCURRENCY: should be the only place where regionMap is modified.
	 *
	 * @param regionIndex Index of database file region
	 * @return
	 * @throws IOException
	 */
	private synchronized MappedByteBuffer createBuffer(int regionIndex) throws IOException {
		while(regionMap.size()<=regionIndex) regionMap.add(null);

		// position of region start
		long pos=((long)regionIndex)*MAX_REGION_SIZE;

		// Expand region size until big enough for current database plus appropriate margin
		int length;
		if (regionIndex==0) {
			length=1<<16;
			while((length<MAX_REGION_SIZE)&&((pos+length)<(dataLength+REGION_MARGIN))) {
				length*=2;
			}
		} else {
			length=(int)MAX_REGION_SIZE;
		}

		length+=REGION_MARGIN; // include margin in buffer length
		MappedByteBuffer mbb= data.getChannel().map(MapMode.READ_WRITE, pos, length);
		regionMap.set(regionIndex, mbb);
		return mbb;
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
		return write(key,0,value,INDEX_START);
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
			return writeNewData(indexPosition,digit,key,ref,PTR_PLAIN);

		} else if (type==PTR_INDEX) {
			// recursively check next level of index
			long newIndexPosition=rawPointer(slotValue); // clear high bits
			return write(key,level+1,ref,newIndexPosition);

		} else if (type==PTR_PLAIN) {
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
			if (BUILD_CHAINS&&(nextSlotValue==0L)) {
				// update current slot to be the start of a chain
				writeSlot(indexPosition,digit,slotValue|PTR_START);

				// write new data pointer to next slot
				long newDataPointer=appendData(key,ref);
				writeSlot(indexPosition,nextDigit,newDataPointer|PTR_CHAIN);

				return ref;
			}
			
			// have collision, so create new index node including the existing pointer
			int nextLevel=level+1;
			// Note: temp should contain key from checkMatchingKey!
			byte[] temp=tempArray.get();
			int nextDigitOfCollided=getDigit(Blob.wrap(temp,0,KEY_SIZE),nextLevel);
			long newIndexPosition=appendLeafIndex(nextLevel,nextDigitOfCollided,slotValue);

			// put index pointer into this index block, setting flags for index node
			writeSlot(indexPosition,digit,newIndexPosition|PTR_INDEX);

			// recursively write this key
			return write(key,nextLevel,ref,newIndexPosition);
		} else if (type==PTR_START) {
			// first check if the start pointer is the right value. if so, just update in place
			if (checkMatchingKey(key, slotValue)) {
				return updateInPlace(slotValue,ref);
			}

			// now scan slots, looking for either the right value or an empty space
			int i=1;
			while (i<isize) {
				int ix=(digit+i)&mask;
				slotValue=readSlot(indexPosition,ix);

				// if we reach an empty location simply write new value as a chain continuation (PTR_CHAIN)
				if (slotValue==0L) {
					return writeNewData(indexPosition,ix,key,ref,PTR_CHAIN);
				}

				// if we are not in a chain, we have reached the maximum chain length. Exit loop and compress.
				if (slotType(slotValue)!=PTR_CHAIN) break;

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
				if (j!=0) writeSlot(indexPosition,movingDigit,0L); // clear the old chain
			}

			// finally update this index with the new index pointer
			writeSlot(indexPosition,digit,newIndexPos|PTR_INDEX);
			return ref;
		} else if (type==PTR_CHAIN) {
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
				if (j!=0) writeSlot(indexPosition,movingDigit,0L); // clear the old chain
			}

			writeSlot(indexPosition,chainStartDigit,newIndexPos|PTR_INDEX);

			// write to the current slot
			return writeNewData(indexPosition,digit,key,ref,PTR_PLAIN);
		} else {
			throw new Error("Unexpected type: "+type);
		}
	}


	/**
	 * Finds the start digit of a chain, stepping backwards from the given digit
	 * @param indexPosition Position of index block
	 * @param digit Position at which PTR_CHAIN is detected, i.e. search begins.
	 * @return
	 * @throws IOException
	 */
	private int seekChainStart(long indexPosition, int digit, int isize) throws IOException {
		int mask=isize-1;
		digit=digit&mask;
		int i=(digit-1)&mask;
		while (i!=digit) {
			long slotValue=readSlot(indexPosition,i);
			if (slotType(slotValue)==PTR_START) return i;
			i=(i-1)&mask;
		}
		throw new Error("Infinite chain?");
	}

	/**
	 * Finds the end digit of a chain, stepping forwards from the given digit
	 * @param indexPosition
	 * @param digit
	 * @return Next index position that is not PTR_CHAIN
	 * @throws IOException
	 */
	private int seekChainEnd(long indexPosition, int digit, int isize) throws IOException {
		int mask=isize-1;
		digit=digit&mask;
		int i=(digit+1)&mask;
		while (i!=digit) {
			long slotValue=readSlot(indexPosition,i);
			if (slotType(slotValue)!=PTR_CHAIN) return i;
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
	@SuppressWarnings("unused")
	private void rewriteExistingData(long indexPosition, int level, long dp) throws IOException {
		int isize=indexSize(level);
		int mask=isize-1;
		
		// index into existing key data to get current digit
		int digit=getDigit(dp,level);

		long currentSlot=readSlot(indexPosition,digit);
		long type = currentSlot&TYPE_MASK;
		if (currentSlot==0L) {
			writeSlot(indexPosition,digit,dp);
		} else if (type==PTR_INDEX) {
			// Write into the new index block (presumably recently created)
			rewriteExistingData(rawPointer(currentSlot),level+1,dp);
		} else if (type==PTR_PLAIN) {
			int newLevel=level+1;

			// expand to a new index block for collision
			long newIndexPosition=appendNewIndexBlock(newLevel);
			rewriteExistingData(newIndexPosition,newLevel,currentSlot);
			rewriteExistingData(newIndexPosition,newLevel,dp);
			writeSlot(indexPosition,digit,newIndexPosition|PTR_INDEX);
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
		MappedByteBuffer mbb=seekMap(pointer);
		byte[] bs=new byte[length];
		mbb.get(bs);
		return Blob.wrap(bs);
	}
	
	public Hash readValueKey(long ptr) throws IOException {
		MappedByteBuffer mbb=seekMap(ptr);
		byte[] bs=new byte[KEY_SIZE];
		mbb.get(bs);
		return Hash.wrap(bs);
	}


	/**
	 * Gets the type of a slot, given the slot value
	 * @param slotValue
	 * @return
	 */
	private long slotType(long slotValue) {
		return slotValue&TYPE_MASK;
	}

	/**
	 * Utility function to truncate file. Won't work if mapped byte buffers are active?
	 * @throws FileNotFoundException
	 * @throws IOException
	 */
	protected void truncateFile() throws FileNotFoundException, IOException {
		try (FileOutputStream fos=new FileOutputStream(file, true)) {
			FileChannel outChan = fos.getChannel() ;
			outChan.truncate(dataLength);
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
				// Update data length
				writeDataLength();
	
				// Send writes to disk
				flush();
				
				regionMap.clear();
	
				data.close();
	
				log.debug("Etch closed on file: "+ getFileName() +" with data length: "+dataLength);
			} catch (IOException e) {
				log.error("Error closing Etch file: "+file,e);
			}
		}
	}


	/**
	 * @return Current data size in bytes
	 */
	public long getDataLength() {
		return dataLength;
	}

	/**
	 * Writes the data length field for the Etch file. S
	 * @throws IOException
	 */
	protected void writeDataLength() throws IOException {
		// write final data length
		MappedByteBuffer mbb=seekMap(OFFSET_FILE_SIZE);
		mbb.putLong(dataLength);
		mbb=null;
	}
	
	/**
	 * Gets the Etch version associated with this file
	 * @return Return Etch version number
	 */
	public short getVersion() {
		// Override when we have more versions
		return ETCH_VERSION;
	}

	/**
	 * Gets the raw pointer for, given the slot value (clears high bits)
	 * @param slotValue Value in slot
	 * @return Pointer extracted from slot value
	 */
	public long rawPointer(long slotValue) {
		return slotValue&~TYPE_MASK;
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
		MappedByteBuffer mbb=seekMap(dataPosition);
		byte[] temp=tempArray.get();
		mbb.get(temp, 0, KEY_SIZE);
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
		
		long position=dataLength;
		byte[] temp=tempArray.get();
		Arrays.fill(temp, 0,indexBlockLength,(byte)0x00);
		
		int ix=POINTER_SIZE*digit; // compute position in block. note: should be already masked above
		Utils.writeLong(temp, ix,dataPointer); // single node
		MappedByteBuffer mbb=seekMap(position);
		mbb.put(temp,0,indexBlockLength); // write index block
		
		// set the datalength to the last available byte in the file after adding index block
		setDataLength(position+indexBlockLength);
		return position;
	}

	/**
	 * Reads a Blob from the database, returning null if not found
	 * @param key Key to read from Store
	 * @return Blob containing the data, or null if not found
	 * @throws IOException If an IO error occurs
	 */
	public <T extends ACell> RefSoft<T> read(AArrayBlob key) throws IOException {
		Counters.etchRead++;

		long pointer=seekPosition(key);
		if (pointer<0) {
			Counters.etchMiss++;
			return null; // not found
		}
		
		return read(key,pointer);
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
		MappedByteBuffer mbb;
		if (key==null) {
			mbb=seekMap(pointer);
			byte[] bs=new byte[KEY_SIZE];
			mbb.get(bs);
			key=Hash.wrap(bs);
		} else {
			// seek to correct position, skipping over key
			mbb=seekMap(pointer+KEY_SIZE);
		}
		
		// get flags byte
		byte flagByte=mbb.get();

		// Get memory size
		long memorySize=mbb.getLong();

		// get Data length
		short length=mbb.getShort();
		byte[] bs=new byte[length];
		mbb.get(bs);
		Blob encoding= Blob.wrap(bs);
		try {
			Hash hash=Hash.wrap(key);
			T cell=store.decode(encoding);
			cell.getEncoding().attachContentHash(hash);

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
		for (MappedByteBuffer mbb: regionMap) {
			if (mbb!=null) mbb.force();
		}
		data.getChannel().force(false);
	}

	/**
	 * Gets the position of a value in the data file from the index
	 * @param key Key value
	 * @return data file offset or -1 if not found
	 * @throws IOException
	 */
	private long seekPosition(AArrayBlob key) throws IOException {
		return seekPosition(key,0,INDEX_START);
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
		MappedByteBuffer mbb=seekMap(pointerIndex);
		long pointer=mbb.getLong();
		return pointer;
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
		
		// Seek to status location
		MappedByteBuffer mbb=seekMap(position+KEY_SIZE);

		// Get current stored values
		int currentFlags=mbb.get();
		int newFlags=Ref.mergeFlags(currentFlags,ref.getFlags()); // idempotent flag merge

		long currentSize=mbb.getLong();

		if (currentFlags==newFlags) return ref;

		// We have a status change, need to increase status of store
		mbb=seekMap(position+KEY_SIZE);
		mbb.put((byte)newFlags);

		// maybe update size, if not already persisted
		if ((currentSize==0L)&&((newFlags&Ref.STATUS_MASK)>=Ref.PERSISTED)) {
			mbb.putLong(ref.getValue().getMemorySize());
		}

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
		MappedByteBuffer mbb=seekMap(position);
		mbb.putLong(slotValue);
	}
	
	public void visitIndex(IEtchIndexVisitor v) throws IOException {
		int[] bs=new int[32];
		visitIndex(v,bs,0,INDEX_START);
	}

	private void visitIndex(IEtchIndexVisitor v, int[] digits, int level, long indexPointer) throws IOException {
		v.visit(this, level, digits, indexPointer);
		int n=indexSize(level);
		for (int i=0; i<n; i++) {
			long slot=readSlot(indexPointer,i);
			if ((slot&TYPE_MASK)==PTR_INDEX) {
				digits[level]=i;
				visitIndex(v,digits,level+1,rawPointer(slot));
			}
		}
	}

	/**
	 * Gets the position of a data block from the given offset into the key
	 * @param key Key value
	 * @param level Level in Etch index (0 = top level)
	 * @param indexPosition offset of the current index block
	 * @return data position for data block or -1 if not found
	 * @throws IOException
	 */
	private long seekPosition(AArrayBlob key, int level, long indexPosition) throws IOException {
		if (level>=MAX_LEVEL) {
			throw new Error("Etch index level exceeded for key: "+key);
		}
		int isize=indexSize(level);
		int mask=isize-1;
		int digit=getDigit(key,level);
		long slotValue=readSlot(indexPosition,digit);
		
		long type=(slotValue&TYPE_MASK);
		if (slotValue==0) {
			// Empty slot i.e. not found
			return -1;
		} else if (type==PTR_INDEX) {
			// recursively check next index node
			long newIndexPosition=rawPointer(slotValue);
			return seekPosition(key,level+1,newIndexPosition);
		} else if (type==PTR_PLAIN) {
			if (checkMatchingKey(key,slotValue)) return slotValue;
			return -1;
		} else if (type==PTR_CHAIN) {
			// continuation of chain from some previous index, therefore key can't be present
			return -1;
		} else if (type==PTR_START) {
			synchronized (this) {
				// start of chain, so scan chain of entries
				int i=0;
				while (i<isize) {
					long ptr=slotValue&(~TYPE_MASK);
					if (checkMatchingKey(key,ptr)) return ptr;

					i++; // advance to next position
					slotValue=readSlot(indexPosition,(digit+i)&mask);
					type=(slotValue&TYPE_MASK);
					if (!(type==PTR_CHAIN)) return -1; // reached end of chain
				}
			}
			return -1;
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
	 * Gets the radix index digit for the specified level
	 * @param dp Data pointer into store
	 * @param level Level of Etch store index to get digit for
	 * @return
	 * @throws IOException 
	 */
	private int getDigit(long dp, int level) throws IOException {
		if (level==0) {
			MappedByteBuffer mbb=seekMap(dp);
			return mbb.getShort()&0xffff;
		} 
		if (level==1) {
			MappedByteBuffer mbb=seekMap(dp+(level+1));
			return mbb.get()&0xFF;
		}
		int bi=(level+4)/2;      // level 2,3 maps to 3 etc.
		boolean hi=(level&1)==0; // we want high byte if even
		MappedByteBuffer mbb=seekMap(dp+bi);
		byte v= mbb.get();
		return (hi?(v>>4):v)&0xf;		
	}

	/**
	 * Gets the index block size for a given level
	 * @param level Level of index block in Etch store
	 * @return Index block size as number of entries
	 */
	public int indexSize(int level) {
		if (level==0) return 65536;
		if (level==1) return 256;
		return 16;
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
		
		long position=dataLength;
		MappedByteBuffer mbb=null;
		
		// set the datalength to the last available byte in the file
		setDataLength(position+sizeBytes);
		
		// Use temporary zero array to fill new index block
		for (int ix=0; ix<sizeBytes; ix+=ZLEN) {
			mbb=seekMap(position+ix);
			mbb.put(ZERO_ARRAY,0,Math.min(sizeBytes-ix,ZLEN));
		}
		return position;
	}

	/**
	 * Appends a new key / value data block. Returns a pointer to the data, with cleared type bits (PTR_PLAIN)
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

		// position ready for append
		final long position=dataLength;
		MappedByteBuffer mbb=seekMap(position);

		// append key
		mbb.put(key.getInternalArray(),key.getInternalOffset(),KEY_SIZE);

		// append flags (1 byte)
		int flags=ref.flagsWithStatus(Math.max(ref.getStatus(),Ref.STORED));
		mbb.put((byte)(flags)); // currently all flags fit in one byte

		// append Memory Size (8 bytes). Initialised to 0L if STORED only.
		mbb.putLong(memorySize);

		// append blob length
		short length=Utils.checkedShort(encoding.count());
		if (length==0) {
			// Blob b=cell.createEncoding();
			throw new Error("Etch trying to write zero length encoding for: "+Utils.getClassName(cell));
		}
		mbb.putShort(length);

		// append blob value
		mbb.put(encoding.getInternalArray(),encoding.getInternalOffset(),length);

		// set the datalength to the last available byte in the file
		setDataLength(position+KEY_SIZE+LABEL_SIZE+LENGTH_SIZE+length);

		// return file position for added data
		return position;
	}

	/**
	 * Sets the total db dataLength in memory. This is the last position in the database
	 * that new data can be written too.
	 *
	 * @param value The new data length to be set
	 *
	 */
	private void setDataLength(long value) {
		// we can never go back! If we do then we will be corrupting the database
		if (value < dataLength) {
			throw new Error("PANIC! New data length is less than the old data length");
		}
		dataLength = value;
	}

	public File getFile() {
		return file;
	}

	public String getFileName() {
		return fileName;
	}

	public synchronized Hash getRootHash() throws IOException {
		MappedByteBuffer mbb=seekMap(OFFSET_ROOT_HASH);
		byte[] bs=new byte[Hash.LENGTH];
		mbb.get(bs);
		return Hash.wrap(bs);
	}

	/**
	 * Writes the root data hash to the Store
	 * @param h Hash value to write
	 * @throws IOException If IO Error occurs
	 */
	public synchronized void setRootHash(Hash h) throws IOException {
		MappedByteBuffer mbb=seekMap(OFFSET_ROOT_HASH);
		byte[] bs=h.getBytes();
		assert(bs.length==Hash.LENGTH);
		mbb.put(bs);
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
		return slot&TYPE_MASK;
	}




}
