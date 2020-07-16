package etch.api;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.logging.Logger;

import convex.core.data.AArrayBlob;
import convex.core.data.Blob;
import convex.core.data.Ref;
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
 * - X bytes monotonic label
 * - 2 bytes data length N
 * - N byes actual data
 */
public class Etch {
	// structural constants for data block
	private static final int KEY_SIZE=32;
	private static final int LABEL_SIZE=1;
	private static final int LENGTH_SIZE=2;
	private static final int POINTER_SIZE=8;
	
	private static final int INDEX_BLOCK_SIZE=POINTER_SIZE*256;
	
	// constants for memory mapping
	private static final int MAX_CHUNK_SIZE=1<<30; // 1GB
	private static final int BUFFER_MARGIN=65536; // 64k margin for writes
	
	/**
	 * Length of dataLength header (64 bit long)
	 */
	private static final int SIZE_HEADER_LENGTH=8;
	
	/**
	 * Start position of first index block
	 * This is immediately after a long data length pointer at the start of the file
	 */
	private static final long INDEX_START=SIZE_HEADER_LENGTH; 
	
	private static final long TYPE_MASK= 0xC000000000000000L;
	private static final long PTR_PLAIN=0x0000000000000000L; // direct pointer to data
	private static final long PTR_INDEX=0x4000000000000000L; // pointer to index block
	private static final long PTR_START=0x8000000000000000L; // start of chained entries
	private static final long PTR_CHAIN=0xC000000000000000L; // chained entries after start

	private static final Logger log=Logger.getLogger(Etch.class.getName());

	
	private final byte[] temp=new byte[INDEX_BLOCK_SIZE];

	/**
	 * Internal pointer to end of database
	 */
	private static long tempIndex=0;

	private File file;
	private final RandomAccessFile data;
	private final ArrayList<MappedByteBuffer> dataMap=new ArrayList<>();
	private long dataLength=0;
	
	private boolean BUILD_CHAINS=true;
	
	private Etch(File dataFile) throws IOException {
		this.file=dataFile;
		if (!dataFile.exists()) dataFile.createNewFile();
		this.data=new RandomAccessFile(dataFile,"rw");
		this.data.getChannel().lock();
		if (dataFile.length()==0) {
			// need to create new file, with data length long and initial index block
			MappedByteBuffer mbb=seekMap(0);
			mbb.putLong(SIZE_HEADER_LENGTH);
			dataLength=SIZE_HEADER_LENGTH; // advance past initial long
			long indexStart=appendNewIndexBlock();
			assert(indexStart==INDEX_START);
			mbb=seekMap(0);
			mbb.putLong(dataLength);
		} else {
			// existing file, so need to read the length pointer
			MappedByteBuffer mbb=seekMap(0);
			long length = mbb.getLong();
			dataLength=length;
		}
		
		// shutdown hook to close file / release lock
		Runtime.getRuntime().addShutdownHook(new Thread() {
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
		data.deleteOnExit();
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
		log.info("Etch created on file: "+file+" with data length: "+etch.dataLength);
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
			throw new Error("Seek out of range in Etch file: "+Utils.toHexString(position));
		}
		int mapIndex=Utils.checkedInt(position/MAX_CHUNK_SIZE); // 1GB chunks
		
		MappedByteBuffer mbb=getBuffer(mapIndex);
		mbb.position(Utils.checkedInt(position-MAX_CHUNK_SIZE*(long)mapIndex));
		return mbb;
	}

	private MappedByteBuffer getBuffer(int mapIndex) throws IOException {
		while(dataMap.size()<=mapIndex) dataMap.add(null);
		MappedByteBuffer mbb=dataMap.get(mapIndex);
		if ((mbb==null)||(mbb.capacity()<dataLength+BUFFER_MARGIN)) mbb=createBuffer(mapIndex);
		return mbb;
	}

	private MappedByteBuffer createBuffer(int mapIndex) throws IOException {
		long pos=mapIndex*(long)MAX_CHUNK_SIZE;
		int length=1<<16;
		while((length<MAX_CHUNK_SIZE)&&((pos+length)<(dataLength+BUFFER_MARGIN))) {
			length*=2;
		}
		length+=BUFFER_MARGIN; // include margin
		MappedByteBuffer mbb= data.getChannel().map(MapMode.READ_WRITE, pos, length);
		dataMap.set(mapIndex, mbb);
		return mbb;
	}

	/**
	 * Writes a key / value pair to the immutable store.
	 * @param key A key value (typically the Hash)
	 * @param value Value data to associate with the key
	 * @throws IOException
	 */
	public synchronized void write(AArrayBlob key, Blob value) throws IOException {
		Counters.etchWrite++;
		write(key,0,value,INDEX_START);
	}
	
	private void write(AArrayBlob key, int keyOffset, Blob value, long indexPosition) throws IOException {
		if (keyOffset>=KEY_SIZE) {
			throw new Error("Offset exceeded for key: "+key);
		}
		
		final int digit=key.get(keyOffset)&0xFF;	
		long slotValue=readSlot(indexPosition,digit);
		long type=slotType(slotValue);
		
		if (slotValue==0L) {
			// empty location, so simply write new value
			writeNewData(indexPosition,digit,key,value,PTR_PLAIN);
		} else if (type==PTR_INDEX) {
			// recursively check next level of index
			long newIndexPosition=slotPointer(slotValue); // clear high bits
			write(key,keyOffset+1,value,newIndexPosition);
		} else if (type==PTR_PLAIN) {
			// existing data pointer (non-zero)
			// check if we have the same value first, otherwise need to resolve conflict
			if (checkMatchingKey(key,slotValue)) return;
			
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
				
				return;
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
			write(key,keyOffset+1,value,newIndexPosition);
		} else if (type==PTR_START) {
			// first check if the start pointer is the right value. if so, bail out with nothing to do
			if (checkMatchingKey(key, slotValue)) return;
			
			// now scan slots, looking for either the right value or an empty space
			int i=1;
			while (i<256) {
				slotValue=readSlot(indexPosition,digit+i);
				
				// if we reach an empty location simply write new value as a chain continuation (PTR_CHAIN)
				if (slotValue==0L) {
					writeNewData(indexPosition,digit+i,key,value,PTR_CHAIN);
					return;
				}
				
				// if we are not in a chain, we have reached the maximum chain length. Exit loop and compress.
				if (slotType(slotValue)!=PTR_CHAIN) break;
				
				// if we found the key itself, return since already stored.
				if (checkMatchingKey(key, slotValue)) return;

				i++;
			}
			
			// we now need to collapse the chain, since it cannot be extended.
			// System.out.println("Compressing chain, offset="+keyOffset+" chain length="+i+" with key "+key+ " indexDat= "+readBlob(indexPosition,2048));
			
			// first we build a new index block, containing our new data
			long newDataPointer=appendData(key,value);
			long newIndexPos=appendLeafIndex(key.get(keyOffset+1),newDataPointer);
			
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
			writeNewData(indexPosition,digit,key,value,PTR_PLAIN);
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
	private synchronized void close() {
		try {
			// write final data length
			MappedByteBuffer mbb=seekMap(0);
			mbb.putLong(dataLength);
			mbb=null;
			
			// Force writes to disk. Probably useful.
			for (MappedByteBuffer m: dataMap) {
				m.force();
			}
			dataMap.clear();
			System.gc();
			
			data.close();
	
			log.info("Etch closed on file: "+data+" with data length: "+dataLength);
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
	 * @param dataPointer Pointer to data. Type bits in MSBs will be ignore.
	 * @return
	 * @throws IOException
	 */
	private boolean checkMatchingKey(AArrayBlob key, long dataPointer) throws IOException {
		long dataPosition=dataPointer&~TYPE_MASK;
		MappedByteBuffer mbb=seekMap(dataPosition);
		mbb.get(temp, 0, KEY_SIZE);
		if (key.equals(temp,0)) {
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
		Arrays.fill(temp, (byte)0x00);
		int ix=POINTER_SIZE*(digit&0xFF);
		Utils.writeLong(dataPointer, temp, ix); // single node
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
	public synchronized Blob read(AArrayBlob key) throws IOException {
		Counters.etchRead++;
		
		long pointer=seekPosition(key);
		if (pointer<0) return null; // not found
		
		MappedByteBuffer mbb=seekMap(pointer+KEY_SIZE+LABEL_SIZE); // skip over key
		short length=mbb.getShort(); // get data length
		byte[] bs=new byte[length];
		mbb.get(bs);
		return Blob.wrap(bs);
	}

	/**
	 * Flushes any changes to persistent storage.
	 * @throws IOException
	 */
	public synchronized void flush() throws IOException {
		for (MappedByteBuffer mbb: dataMap) {
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
	 * @throws IOException
	 */
	private void writeNewData(long indexPosition, int digit, AArrayBlob key, Blob value, long type) throws IOException {
		long newDataPointer=appendData(key,value)|type;
		writeSlot(indexPosition, digit, newDataPointer);
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

		int digit=key.get(offset)&0xFF;	
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
	private long appendData(AArrayBlob key,Blob value) throws IOException {
		assert(key.length()==KEY_SIZE);
		
		long position=dataLength;
		MappedByteBuffer mbb=seekMap(position);
		
		// append key
		mbb.put(key.getInternalArray(),key.getOffset(),KEY_SIZE);
		
		// append label
		mbb.put((byte)Ref.STORED);
		
		// append blob length
		short length=Utils.checkedShort(value.length());
		mbb.putShort(length);
		
		// append blob value
		mbb.put(value.getInternalArray(),value.getOffset(),length);
		
		// update total data length and return
		dataLength=position+KEY_SIZE+LABEL_SIZE+LENGTH_SIZE+length;
		return position;
	}

	public File getFile() {
		return file;
	}
}
