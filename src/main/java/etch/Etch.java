package etch;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

import convex.api.Shutdown;
import convex.core.Constants;
import convex.core.data.AArrayBlob;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Format;
import convex.core.data.Hash;
import convex.core.data.Ref;
import convex.core.data.RefSoft;
import convex.core.util.Counters;
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
 * Radix tree index blocks are 256-way arrays of 8 byte pointers.
 * 
 * To avoid creating too many index blocks when collisions occur, a chained entry list inside is created
 * in unused space in index blocks. Once there is no more space, chains are collapsed to a new index block.
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
 *    - 8 bytes Memory Size (TODO: might be negative for unknown?)
 * - 2 bytes data length N (a short)
 * - N byes actual data
 */
public class Etch {
	// structural constants for data block
	private static final int KEY_SIZE=32;
	private static final int LABEL_SIZE=1+8; // status plus Memory Size long
	private static final int LENGTH_SIZE=2;
	private static final int POINTER_SIZE=8;
	
	/**
	 * Index block is fixed size with 256 entries 
	 */
	private static final int INDEX_BLOCK_SIZE=POINTER_SIZE*256;
	
	// constants for memory mapping buffers into manageable regions
	private static final int MAX_REGION_SIZE=1<<30; // 1GB seems reasonable
	private static final int REGION_MARGIN=65536; // 64k margin for writes past end of current buffer
	
	/**
	 * Magic number for Etch files, must be first 2 bytes
	 */
	private static final byte[] MAGIC_NUMBER=Utils.hexToBytes("e7c6");
	
	private static final int SIZE_HEADER_MAGIC=2;
	private static final int SIZE_HEADER_FILESIZE=8;
	private static final int SIZE_HEADER_ROOT=32;

	/**
	 * Length of header, including:
	 * - Magic number "e7c6" (2 bytes)
	 * - File size (8 bytes)
	 * - Root hash (32 bytes)
	 * 
	 * "The Ultimate Answer to Life, The Universe and Everything is... 42!"
     * - Douglas Adams, The Hitchhiker's Guide to the Galaxy
	 */
	private static final int SIZE_HEADER=SIZE_HEADER_MAGIC+SIZE_HEADER_FILESIZE+SIZE_HEADER_ROOT;
	
	protected static final long OFFSET_FILE_SIZE = SIZE_HEADER_MAGIC;
	protected static final long OFFSET_ROOT_HASH = SIZE_HEADER_MAGIC+SIZE_HEADER_FILESIZE;

	/**
	 * Start position of first index block
	 * This is immediately after a long data length pointer at the start of the file
	 */
	private static final long INDEX_START=SIZE_HEADER; 
	
	private static final long TYPE_MASK= 0xC000000000000000L;
	private static final long PTR_PLAIN=0x0000000000000000L; // direct pointer to data
	private static final long PTR_INDEX=0x4000000000000000L; // pointer to index block
	private static final long PTR_START=0x8000000000000000L; // start of chained entries
	private static final long PTR_CHAIN=0xC000000000000000L; // chained entries after start

	private static final Logger log=Logger.getLogger(Etch.class.getName());

	private static final Level LEVEL_STORE=Level.FINE;
	
	
	/**
	 * Temporary byte array for writer. Must not be used by readers.
	 */
	private final ThreadLocal<byte[]> tempArray=new ThreadLocal<>() {
		@Override
		public byte[]  initialValue() {
			return new byte[INDEX_BLOCK_SIZE];
		}
	};

	/**
	 * Internal pointer to end of database
	 */
	private static long tempIndex=0;

	private final File file;
	private final RandomAccessFile data;
	
	/**
	 * List of MappedByteBuffers for each region of the database file.
	 */
	private final ArrayList<MappedByteBuffer> regionMap=new ArrayList<>();
	
	private long dataLength=0;
	
	private boolean BUILD_CHAINS=true;
	
	private Etch(File dataFile) throws IOException {
		// Ensure we have a RandomAccessFile that exists
		this.file=dataFile;
		if (!dataFile.exists()) dataFile.createNewFile();
		this.data=new RandomAccessFile(dataFile,"rw");
		
		// Try to exclusively lock the Etch database file
		FileChannel fileChannel=this.data.getChannel();
		FileLock lock=fileChannel.tryLock();
		if (lock==null) {
			log.log(Level.SEVERE,"Unable to obtain lock on file: "+dataFile);
			throw new IOException("File lock failed");
		}
		
		// at this point, we have an exclusive lock on the database file.
		
		if (dataFile.length()==0) {
			// Need to populate  new file, with data length long and initial index block
			MappedByteBuffer mbb=seekMap(0);
			mbb.put(MAGIC_NUMBER);
			
			// write zeros (temp is newly empty) for file size and root. Will fix later
			int headerZeros=SIZE_HEADER_FILESIZE+SIZE_HEADER_ROOT;
			byte[] temp=new byte[headerZeros]; 
			mbb.put(temp,0,headerZeros);
			dataLength=SIZE_HEADER; // advance past initial long
			
			// add an index block
			long indexStart=appendNewIndexBlock();
			assert(indexStart==INDEX_START);
			
			// ensure data length is initially correct
			mbb=seekMap(SIZE_HEADER_MAGIC);
			mbb.putLong(dataLength);
		} else {
			// existing file, so need to read the length pointer
			MappedByteBuffer mbb=seekMap(0);
			byte[] check=new byte[2];
			mbb.get(check);
			if(!Arrays.equals(MAGIC_NUMBER, check)) {
				throw new IOException("Bad magic number! Probably not an Etch file: "+dataFile);
			}
			
			long length = mbb.getLong();
			dataLength=length;
		}
		
		// shutdown hook to close file / release lock
		convex.api.Shutdown.addHook(Shutdown.ETCH,new Runnable() {
		    public void run() {
		        close();
		    }
		});
	}
	
	/**
	 * Create an Etch instance using a temporary file.
	 * @return The new Etch instance
	 * @throws IOException
	 */	
	public static Etch createTempEtch() throws IOException {
		Etch newEtch =  createTempEtch("etch-"+tempIndex);
		tempIndex++;
		return newEtch;
	}
	
	/**
	 * Create an Etch instance using a temporary file with a specific file prefix.
	 * @param temporary file prefix to use
	 * @return The new Etch instance
	 * @throws IOException
	 */	
	public static Etch createTempEtch(String prefix) throws IOException {
		File data = File.createTempFile(prefix+"-", null);
		if (Constants.ETCH_DELETE_TEMP_ON_EXIT) data.deleteOnExit();
		return new Etch(data);
	}
	
	/**
	 * Create an Etch instance using the specified file
	 * @param file
	 * @return The new Etch instance
	 * @throws IOException
	 */
	public static Etch create(File file) throws IOException {
		Etch etch= new Etch(file);
		log.log(LEVEL_STORE,"Etch created on file: "+file+" with data length: "+etch.dataLength);
		return etch;
	}
	
	/**
	 * Gets the MappedByteBuffer for a given position, seeking to the specified location.
	 * Type flags are ignored if included in the position pointer.
	 * 
	 * @param position Target position for the MappedByteBuffer
	 * @return MappedByteBuffer instance with correct position.
	 * @throws IOException
	 */
	private MappedByteBuffer seekMap(long position) throws IOException {
		position=slotPointer(position); // ensure we don't have any pesky type bits
		
		if ((position<0)||(position>dataLength)) {
			throw new Error("Seek out of range in Etch file: position="+Utils.toHexString(position)+ " dataLength="+Utils.toHexString(dataLength)+" file="+file.getName());
		}
		int mapIndex=Utils.checkedInt(position/MAX_REGION_SIZE); // 1GB chunks
		
		MappedByteBuffer mbb=(MappedByteBuffer) getBuffer(mapIndex).duplicate();
		mbb.position(Utils.checkedInt(position-MAX_REGION_SIZE*(long)mapIndex));
		return mbb;
	}

	private MappedByteBuffer getBuffer(int regionIndex) throws IOException {
		// Get current mapped region, or null if out of range
		int regionMapSize=regionMap.size();
		MappedByteBuffer mbb=(regionIndex<regionMapSize)?regionMap.get(regionIndex):null;
		
		// Call createBuffer if mapped region does not exist, or is too small
		if ((mbb==null)||(mbb.capacity()<dataLength+REGION_MARGIN)) mbb=createBuffer(regionIndex);
		
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
		
		long pos=regionIndex*(long)MAX_REGION_SIZE;
		
		// Expand region size until big enough for current database plus appropriate margin
		int length=1<<16;
		while((length<MAX_REGION_SIZE)&&((pos+length)<(dataLength+REGION_MARGIN))) {
			length*=2;
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
	 * @throws IOException
	 */
	public synchronized Ref<ACell> write(AArrayBlob key, Ref<ACell> value) throws IOException {
		Counters.etchWrite++;
		return write(key,0,value,INDEX_START);
	}
	
	private Ref<ACell> write(AArrayBlob key, int keyOffset, Ref<ACell> value, long indexPosition) throws IOException {
		if (keyOffset>=KEY_SIZE) {
			throw new Error("Offset exceeded for key: "+key);
		}
		
		final int digit=key.byteAt(keyOffset)&0xFF;	
		long slotValue=readSlot(indexPosition,digit);
		long type=slotType(slotValue);
		
		if (slotValue==0L) {
			// empty location, so simply write new value
			return writeNewData(indexPosition,digit,key,value,PTR_PLAIN);
			
		} else if (type==PTR_INDEX) {
			// recursively check next level of index
			long newIndexPosition=slotPointer(slotValue); // clear high bits
			return write(key,keyOffset+1,value,newIndexPosition);
			
		} else if (type==PTR_PLAIN) {
			// existing data pointer (non-zero)
			// check if we have the same value first, otherwise need to resolve conflict
			// This should have the current (potential collision) key in tempArray
			if (checkMatchingKey(key,slotValue)) {
				return updateInPlace(slotValue,value);
			}
			byte[] temp=tempArray.get();
			
			// we need to check the next slot position to see if we can extend to a chain
			int nextDigit=digit+1;
			long nextSlotValue=readSlot(indexPosition,nextDigit);
			
			// if next slot is empty, we can make a chain!
			if (BUILD_CHAINS&&(nextSlotValue==0L)) {
				// update current slot to be the start of a chain
				writeSlot(indexPosition,digit,slotValue|PTR_START); 
				
				// write new data pointer to next slot
				long newDataPointer=appendData(key,value);
				writeSlot(indexPosition,nextDigit,newDataPointer|PTR_CHAIN);
				
				return value;
			}
			
			if (keyOffset>=KEY_SIZE-1) {
				throw new Error("Unexpected collision at max offset for key: "+key+" with existing key: "+Blob.wrap(temp,0,KEY_SIZE));
			}
			
			// have collision, so create new index node including the existing pointer
			int nextDigitOfCollided=temp[keyOffset+1]&0xFF;
			long newIndexPosition=appendLeafIndex(nextDigitOfCollided,slotValue);
			
			// put index pointer into this index block, setting flags for index node
			writeSlot(indexPosition,digit,newIndexPosition|PTR_INDEX); 
			
			// recursively write this key
			return write(key,keyOffset+1,value,newIndexPosition);
		} else if (type==PTR_START) {
			// first check if the start pointer is the right value. if so, bail out with nothing to do
			if (checkMatchingKey(key, slotValue)) {
				return updateInPlace(slotValue,value);
			}
			
			// now scan slots, looking for either the right value or an empty space
			int i=1;
			while (i<256) {
				slotValue=readSlot(indexPosition,digit+i);
				
				// if we reach an empty location simply write new value as a chain continuation (PTR_CHAIN)
				if (slotValue==0L) {
					return writeNewData(indexPosition,digit+i,key,value,PTR_CHAIN);
				}
				
				// if we are not in a chain, we have reached the maximum chain length. Exit loop and compress.
				if (slotType(slotValue)!=PTR_CHAIN) break;
				
				// if we found the key itself, return since already stored.
				if (checkMatchingKey(key, slotValue)) {
					return updateInPlace(slotValue,value);
				}

				i++;
			}
			
			// we now need to collapse the chain, since it cannot be extended.
			// System.out.println("Compressing chain, offset="+keyOffset+" chain length="+i+" with key "+key+ " indexDat= "+readBlob(indexPosition,2048));
			
			// first we build a new index block, containing our new data
			long newDataPointer=appendData(key,value);
			long newIndexPos=appendLeafIndex(key.byteAt(keyOffset+1),newDataPointer);
			
			// for each element in chain, move existing data to new index block. i is the length of chain
			for (int j=0; j<i; j++) {
				int movingDigit=digit+j;
				long movingSlotValue=readSlot(indexPosition,movingDigit);
				long dp=slotPointer(movingSlotValue); // just the raw pointer
				writeExistingData(newIndexPos,keyOffset+1,dp);
				if (j!=0) writeSlot(indexPosition,movingDigit,0L); // clear the old chain
			}
			
			// finally update this index with the new index pointer
			writeSlot(indexPosition,digit,newIndexPos|PTR_INDEX);
			return value;
		} else if (type==PTR_CHAIN) {
			// need to collapse existing chain
			int chainStartDigit=seekChainStart(indexPosition,digit);
			if (chainStartDigit==digit) throw new Error("Can't start chain at this digit? "+digit);
			int chainEndDigit=seekChainEnd(indexPosition,digit);

			int n=(chainStartDigit==chainEndDigit)?256:(chainEndDigit-chainStartDigit)&0xFF;
			long newIndexPos=appendNewIndexBlock();
			for (int j=0; j<n; j++) {
				int movingDigit=chainStartDigit+j;
				long movingSlotValue=readSlot(indexPosition,movingDigit);
				long dp=slotPointer(movingSlotValue); // just the raw pointer
				writeExistingData(newIndexPos,keyOffset+1,dp);
				if (j!=0) writeSlot(indexPosition,movingDigit,0L); // clear the old chain
			}
			
			writeSlot(indexPosition,chainStartDigit,newIndexPos|PTR_INDEX);

			// write to the current slot
			return writeNewData(indexPosition,digit,key,value,PTR_PLAIN);
		} else {
			throw new Error("Unexpected type: "+type);
		}
	}


	/**
	 * Finds the start digit of a chain, stepping backwards from the given digit
	 * @param indexPosition
	 * @param digit
	 * @return
	 * @throws IOException 
	 */
	private int seekChainStart(long indexPosition, int digit) throws IOException {
		digit=digit&0xFF;
		int i=(digit-1)&0xFF;
		while (i!=digit) {
			long slotValue=readSlot(indexPosition,i);
			if (slotType(slotValue)==PTR_START) return i;
			i=(i-1)&0xFF;
		}
		throw new Error("Infinite chain?");
	}
	
	/**
	 * Finds the end digit of a chain, stepping forwards from the given digit
	 * @param indexPosition
	 * @param digit
	 * @return
	 * @throws IOException 
	 */
	private int seekChainEnd(long indexPosition, int digit) throws IOException {
		digit=digit&0xFF;
		int i=(digit+1)&0xFF;
		while (i!=digit) {
			long slotValue=readSlot(indexPosition,i);
			if (slotType(slotValue)!=PTR_CHAIN) return i;
			i=(i+1)&0xFF;
		}
		throw new Error("Infinite chain?");
	}

	/**
	 * Writes existing data into an index block. Existing data assumed to be unique,
	 * so we don't check for key clashes.
	 * 
	 * We also don't do chaining, assume clashes unlikely, and that the block given has
	 * no existing chains.
	 *
	 * @param newIndexPos
	 * @param keyOffsetlong
	 * @param dp Raw data pointer
	 * @throws IOException 
	 */
	@SuppressWarnings("unused")
	private void writeExistingData(long indexPosition, int keyOffset, long dp) throws IOException {
		
		// index into existing key data to get current digit
		MappedByteBuffer mbb=seekMap(dp+keyOffset);
		int digit=mbb.get()&0xFF;
		
		long currentSlot=readSlot(indexPosition,digit);
		long type = currentSlot&TYPE_MASK;
		if (currentSlot==0L) {
			writeSlot(indexPosition,digit,dp);
		} else if (type==PTR_INDEX) {
			writeExistingData(slotPointer(currentSlot),keyOffset+1,dp);
		} else if (type==PTR_PLAIN) {
			if (keyOffset+1>=KEY_SIZE) {
				Blob bx=readBlob(indexPosition,2048);
				Blob bx2=readBlob(currentSlot,34);
				Blob bx3=readBlob(dp,34);
				throw new Error("Overflowing key size - key collision? index="+Utils.toHexString(indexPosition)+" dataPointer="+Utils.toHexString(dp)+" Key: "+readBlob(dp,32));
			}
			
			// expand to a new index block for collision
			long newIndexPosition=appendNewIndexBlock();
			writeExistingData(newIndexPosition,keyOffset+1,dp);
			writeExistingData(newIndexPosition,keyOffset+1,currentSlot);
			writeSlot(indexPosition,digit,newIndexPosition|PTR_INDEX);
		} else {
			throw new Error("Unexpected type: "+type);
		}
	}

	/**
	 * Reads a blob of the specified length from storage
	 * @param pointer
	 * @param length
	 * @return
	 * @throws IOException
	 */
	private Blob readBlob(long pointer, int length) throws IOException {
		MappedByteBuffer mbb=seekMap(pointer);
		byte[] bs=new byte[length];
		mbb.get(bs);
		return Blob.wrap(bs);
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
	synchronized void close() {
		if (!(data.getChannel().isOpen())) return; // already closed
		try {
			// write final data length
			MappedByteBuffer mbb=seekMap(OFFSET_FILE_SIZE);
			mbb.putLong(dataLength);
			mbb=null;
			
			// Force writes to disk. Probably useful.
			for (MappedByteBuffer m: regionMap) {
				m.force();
			}
			regionMap.clear();
			System.gc();
			
			data.close();
	
			log.log(LEVEL_STORE,"Etch closed on file: "+data+" with data length: "+dataLength);
		} catch (IOException e) {
			log.severe("Error closing Etch file: "+file);
			e.printStackTrace();
		}
	}

	/**
	 * Gets the raw pointer for, given the slot value (clears high bits)
	 * @param slotValue
	 * @return
	 */
	private long slotPointer(long slotValue) {
		return slotValue&~TYPE_MASK;
	}

	/**
	 * Checks if the key matches the data at the specified pointer position
	 * @param key
	 * @param dataPointer Pointer to data. Type bits in MSBs will be ignored.
	 * @return
	 * @throws IOException
	 */
	private boolean checkMatchingKey(AArrayBlob key, long dataPointer) throws IOException {
		long dataPosition=dataPointer&~TYPE_MASK;
		MappedByteBuffer mbb=seekMap(dataPosition);
		byte[] temp=tempArray.get();
		mbb.get(temp, 0, KEY_SIZE);
		if (key.equalsBytes(temp,0)) {
			// key already in store
			return true;
		}
		return false;
	}
	
	/**
	 * Appends a leaf index block including exactly one data pointer, at the specified digit position
	 * @param digit Digit position for the data pointer to be stored at (0..255, high bits ignored)
	 * @param dataPointer Single data pointer to include in new index block
	 * @return the position of the new index block
	 * @throws IOException
	 */
	private long appendLeafIndex(int digit, long dataPointer) throws IOException {
		long position=dataLength;
		byte[] temp=tempArray.get();
		Arrays.fill(temp, (byte)0x00);
		int ix=POINTER_SIZE*(digit&0xFF);
		Utils.writeLong(temp, ix,dataPointer); // single node
		MappedByteBuffer mbb=seekMap(position);
		mbb.put(temp); // write full index block
		dataLength=position+INDEX_BLOCK_SIZE;
		return position;
	}

	/**
	 * Reads a Blob from the database, returning null if not found
	 * @param key
	 * @return Blob containing the data, or null if not found
	 * @throws IOException
	 */
	public Ref<ACell> read(AArrayBlob key) throws IOException {
		Counters.etchRead++;
		
		long pointer=seekPosition(key);
		if (pointer<0) {
			Counters.etchMiss++;
			return null; // not found
		}
		
		// seek to correct position, skipping over key
		MappedByteBuffer mbb=seekMap(pointer+KEY_SIZE); 
		
		// get Status byte
		byte status=mbb.get();
		
		// Get memory size
		long memorySize=mbb.getLong();

		// get Data length
		short length=mbb.getShort(); 
		byte[] bs=new byte[length];
		mbb.get(bs);
		Blob data= Blob.wrap(bs);
		try {
			Hash hash=Hash.wrap(key);
			ACell cell=Format.read(data);
			data.attachContentHash(hash);
			cell.attachEncoding(data);
			
			if (memorySize>=0) {
				// need to attach memory size for cell
				cell.attachMemorySize(memorySize);
			}
			
			Ref<ACell> ref=RefSoft.create(cell, status);
			cell.attachRef(ref);
			
			return ref;
		} catch (Exception e) {
			throw new Error("Failed to read data in etch store: "+data.toHexString()+" status = "+status+" length ="+length+" pointer = "+Utils.toHexString(pointer)+ " memorySize="+memorySize,e);
		}
	}

	/**
	 * Flushes any changes to persistent storage.
	 * @throws IOException
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
	 * Gets the slot value at the specified digit position in an index block.
	 * @param indexPosition Position of index block
	 * @param digit Digit of value 0..255 (high bits will be ignored)
	 * @return Pointer value (including type bits in MSBs)
	 * @throws IOException
	 */
	private long readSlot(long indexPosition, int digit) throws IOException {
		long pointerIndex=indexPosition+POINTER_SIZE*(digit&0xFF);	
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
	private Ref<ACell> writeNewData(long indexPosition, int digit, AArrayBlob key, Ref<ACell> value, long type) throws IOException {
		long newDataPointer=appendData(key,value)|type;
		writeSlot(indexPosition, digit, newDataPointer);
		return value.withMinimumStatus(Ref.STORED);
	}
	
    /**
     * Updates a Ref in place at the specified position. Assumes data not changed.
     * @param position Data position in storage file
     * @param ref
     * @return
     * @throws IOException 
     */
	private Ref<ACell> updateInPlace(long position, Ref<ACell> ref) throws IOException {
		// Seek to status location
		MappedByteBuffer mbb=seekMap(position+KEY_SIZE);
		
		// Get current stored values
		byte currentStatus=mbb.get();
		long currentSize=mbb.getLong();
		
		byte targetStatus=(byte)ref.getStatus();
		if (currentStatus<targetStatus) {
			// need to increase status of store
			mbb=seekMap(position+KEY_SIZE);
			mbb.put(targetStatus);
			
			// maybe update size, if not already persisted
			if ((currentSize==0L)&&(targetStatus>=Ref.PERSISTED)) {
				mbb.putLong(ref.getValue().getMemorySize());
			}
			
			return ref;
		} else {
			// possibly update value status to reflect current store
			return ref.withMinimumStatus(currentStatus);
		}
		

		
	}

	/**
	 * Writes a slot value to an index block.
	 * 
	 * @param indexPosition
	 * @param digit Digit radix position in index block (0..255), high bits are ignored
	 * @param slotValue
	 * @throws IOException
	 */
	private void writeSlot(long indexPosition, int digit, long slotValue) throws IOException {
		long position=indexPosition+(digit&0xFF)*POINTER_SIZE;
		MappedByteBuffer mbb=seekMap(position);
		mbb.putLong(slotValue);
	}

	/**
	 * Gets the position of a data block from the given offset into the key
	 * @param key Key value
	 * @param offset Offset in number of bytes into key value for next step of search
	 * @param indexPosition offset of the current index block
	 * @return data position for data block or -1 if not found
	 * @throws IOException
	 */
	private long seekPosition(AArrayBlob key, int offset, long indexPosition) throws IOException {
		if (offset>=KEY_SIZE) {
			throw new Error("Offset exceeded for key: "+key);
		}

		int digit=key.byteAt(offset)&0xFF;	
		long slotValue=readSlot(indexPosition,digit);
		long type=(slotValue&TYPE_MASK);
		if (slotValue==0) { 
			// Empty slot i.e. not found
			return -1; 
		} else if (type==PTR_INDEX) {
			// recursively check next index node
			long newIndexPosition=slotPointer(slotValue);
			return seekPosition(key,offset+1,newIndexPosition);
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
				while (i<256) {
					long ptr=slotValue&(~TYPE_MASK);
					if (checkMatchingKey(key,ptr)) return ptr;
					
					i++; // advance to next position
					slotValue=readSlot(indexPosition,digit+i);
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
	 * Append a new index block to the store file. The new Index block will be initially empty,
	 * i.e. filled completely with zeros.
	 * @return The location of the newly added index block.
	 * @throws IOException
	 */
	private long appendNewIndexBlock() throws IOException {
		long position=dataLength;
		byte[] temp=tempArray.get();
		MappedByteBuffer mbb=seekMap(position);
		Arrays.fill(temp,(byte)0);
		mbb.put(temp);
		dataLength=position+INDEX_BLOCK_SIZE;
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
	private long appendData(AArrayBlob key,Ref<ACell> value) throws IOException {
		assert(key.count()==KEY_SIZE);
		
		// Get relevant values for writing
		// probably need to call these first, might move mbb position?
		ACell cell=value.getValue();
		Blob encoding=cell.getEncoding();
		int status=value.getStatus();
		
		long memorySize=0L;
		if (status>=Ref.PERSISTED) {
			memorySize=cell.getMemorySize();
		}

		// position ready for append
		final long position=dataLength;
		MappedByteBuffer mbb=seekMap(position);
		
		// append key
		mbb.put(key.getInternalArray(),key.getOffset(),KEY_SIZE);
		
		// append status label (1 byte)
		mbb.put((byte)(Math.max(value.getStatus(),Ref.STORED)));
		
		// append Memory Size (8 bytes). Initialised to 0L is STORED only.
		mbb.putLong(memorySize);
		
		// append blob length
		short length=Utils.checkedShort(encoding.count());
		if (length==0) {
			// Blob b=cell.createEncoding();
			throw new Error("Etch trying to write zero length encoding for: "+Utils.getClassName(cell));
		}
		mbb.putShort(length);
		
		// append blob value
		mbb.put(encoding.getInternalArray(),encoding.getOffset(),length);
		
		// update total data length and return
		dataLength=mbb.position();
		
		if (dataLength!=position+KEY_SIZE+LABEL_SIZE+LENGTH_SIZE+length) {
			System.out.println("PANIC!");
		}
		
		// return file position for added data
		return position;
	}

	public File getFile() {
		return file;
	}

	public synchronized Hash getRootHash() throws IOException {
		MappedByteBuffer mbb=seekMap(OFFSET_ROOT_HASH);
		byte[] bs=new byte[Hash.LENGTH];
		mbb.get(bs);
		return Hash.wrap(bs);
	}

	public synchronized void setRootHash(Hash h) throws IOException {
		MappedByteBuffer mbb=seekMap(OFFSET_ROOT_HASH);
		byte[] bs=h.getBytes();
		assert(bs.length==Hash.LENGTH);
		mbb.put(bs);
	}
}
