package convex.core.data.util;

import java.nio.ByteBuffer;

import convex.core.data.ABlob;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Strings;
import convex.core.data.prim.CVMChar;
import convex.core.util.Utils;
import convex.core.exceptions.Panic;

/**
 * Similar to Java StringBuilder designed for concatenating multiple small Blobs to produce a larger 
 * Blob. 
 */
public class BlobBuilder {

	/**
	 * Accumulator Blob, always a canonical Blob with filled chunks
	 */
	protected ABlob acc=Blob.EMPTY; 
	
	/**
	 * Accumulator array for up to a single chunk worth of bytes
	 * may be appended to, but never mutated below current position to allow safe wrapping in Blobs
	 */
	protected byte[] tail=null; // 
	
	/**
	 * Total count of bytes in this BlobBuilder
	 */
	protected long count=0;
	
	public BlobBuilder() {
		count=0;
	}
	
	public BlobBuilder(ABlob blob) {
		this();
		append(blob);
	}

	/**
	 * Available bytes to add to current tail before overflow
	 * @return
	 */
	private int spare() {
		return (int)(Blob.CHUNK_LENGTH-(count-acc.count()));
	}
	
	/**
	 * Available bytes to add to current tail before overflow
	 * @return
	 */
	private int arrayPos() {
		return Utils.checkedInt(count-acc.count());
	}
	
	public BlobBuilder append(AString a) {
		return append(a.toBlob());
	}
	
	public BlobBuilder append(ABlob b) {
		long blen=b.count();
		if (blen==0) return this; // nothing to do
		
		long spare=spare();
		
		if (blen<=spare) {
			// Does not overflow current chunk
			ensureArray(arrayPos()+blen);
			b.getBytes(tail,arrayPos());
			count+=blen;
			if (blen==spare) completeChunk();
			return this;
		} else {
			// append to fill current chunk
			append(b.slice(0, spare));
			
			// Index over offset into b
			for (long off=spare; off<blen; off+=Blob.CHUNK_LENGTH) {
				long take=Math.min(Blob.CHUNK_LENGTH, blen-off);
				append(b.slice(off,off+take));
			}
			return this;
		}
	}

	/**
	 * Completes a chunk to the accumulated Blob and clears the tail
	 */
	private void completeChunk() {
		if (tail.length!=Blob.CHUNK_LENGTH) throw new IllegalStateException("tail not complete! Has length: "+tail.length);
		Blob b=Blob.wrap(tail,0,arrayPos());
		acc=acc.append(b);
		tail=null;
	}

	/**
	 * Ensures array length at least equal to n
	 * @param n
	 */
	private void ensureArray(long n) {
		if (n>Blob.CHUNK_LENGTH) throw new IllegalStateException("Invalid array size request: "+n);
		if (tail==null) {
			tail=new byte[Utils.checkedInt(Math.min(Blob.CHUNK_LENGTH,n*2))];
		}
		
		if (tail.length>=n) return;
		
		// Must copy this many bytes
		int save=arrayPos();
		int newLen=Math.min(Blob.CHUNK_LENGTH,Math.max((int)n, tail.length*2));
		byte[] newTail=new byte[newLen];
		
		System.arraycopy(tail, 0, newTail, 0, save);
		tail=newTail;
	}
	
	/**
	 * Gets a blob representing the current contents of this BlobBuilder. 
	 * 
	 * O(log n) but quite high overhead, avoid calling too frequently
	 * 
	 * @return Blob contents of this BlobBuilder
	 */
	public ABlob toBlob() {
		ABlob result=acc;
		if (tail==null) return acc;
		
		result=result.append(Blob.wrap(tail,0,arrayPos()));	
		if (result.count()!=count) throw new Panic("Invalid count!!");
		return result;
	}
	
	/**
	 * Takes a slice of the Blob currently under construction. May be more efficient than toBLob().slice(...).
	 * @param start Start index of slice (inclusive)
	 * @param end End index of slice (exclusive)
	 * @return Slice of Blob under construction
	 */
	public ABlob slice(long start, long end) {
		if ((start<0)||(start>count)) throw new IndexOutOfBoundsException("Invalid start: "+start);
		if ((end<start)||(end>count)) throw new IndexOutOfBoundsException("Invalid end: "+end);
		long length=end-start;
		if (length==count) return toBlob();
		
		long split=acc.count();
		if (end<=split) return acc.slice(start,end);
		if (start>=split) return Blob.wrap(tail,(int)(start-split),(int)length);
		return toBlob().slice(start,end);
	}
	
	public AString getCVMString() {
		ABlob result=toBlob();
		return Strings.create(result);
	}

	/**
	 * Current number of bytes accumulated in this BlobBuilder
	 * @return Number of bytes
	 */
	public long count() {
		return count;
	}

	public void append(String string) {
		append(Strings.create(string));
	}
	
	public BlobBuilder append(byte b) {
		int spare=spare();
		if (spare<1) throw new Panic("BlobBuilder should always have spare bytes but was: "+spare);
		ensureArray(arrayPos()+1);
		tail[Blob.CHUNK_LENGTH-spare]=b;
		count+=1;
		if (spare==1) {
			completeChunk();
		}
		return this;
	}
	
	public void append(byte[] bs) {
		append(bs,0,bs.length);
	}
	
	public void append(byte[] bs, int offset, int length) {
		while (length>0) {
			int split=Math.min(length, spare());
			int pos=arrayPos();
			ensureArray(pos+split);
			System.arraycopy(bs, offset, tail, pos, split);
			count+=split;
			length-=split;
			offset+=split;
			if (spare()==0) {
				completeChunk();
			}
		}
	}

	/**
	 * Append a character to this Blob. Converts to UTF-8
	 * @param c Java character (UTF-16)
	 * @return This BlobBuilder
	 */
	public BlobBuilder append(char c) {
		if (c<128) {
			return append((byte)c);
		} else {
			return append(CVMChar.create(c));
		}
	}
	
	public void appendHexByte(byte b) {
		append(Utils.toHexChar((b & 0xF0) >>> 4));
		append(Utils.toHexChar((b & 0xF)));
	}

	/**
	 * Append a CVM character to this Blob
	 * @param c CVM Character
	 * @return This BlobBuilder
	 */
	public BlobBuilder append(CVMChar c) {
		return append(c.toUTFBlob());
	}

	/**
	 * Checks if this BlobBuilder is less than or equal to the specified limit
	 * @param limit Maximum allowable length of BlobBuilder
	 * @return true if within limit, false if exceeded
	 */
	public boolean check(long limit) {
		return count<=limit;
	}
	
	/**
	 * Appends remaining bytes from a ByteBuffer to this BlobBuilder
	 * @param bb ByteBuffer to append
	 */
	public void append(ByteBuffer bb) {
		
		// Might need to fill current chunk
		if (arrayPos()>0) {
			appendToFillChunk(bb);
		}
		
		// Take whole chunks
		int n=bb.remaining();
		while (n>=Blob.CHUNK_LENGTH) {
			byte[] bs=new byte[Blob.CHUNK_LENGTH];
			bb.get(bs);
			acc=acc.append(Blob.wrap(bs));
			n-=Blob.CHUNK_LENGTH;
			count=acc.count();
		}
		if (n<=0) return;
		
		// Get remaining bytes for last chunk
		appendToFillChunk(bb);
	}
	
	private void appendToFillChunk(ByteBuffer bb) {
		int n=bb.remaining();
		int fill=Math.min(spare(), n);
		if (fill>0) {	
			ensureArray(arrayPos()+fill);
			bb.get(tail, arrayPos(), fill);
			count+=fill;
			if (arrayPos()==Blob.CHUNK_LENGTH) completeChunk();
		}
	}

	/**
	 * Clears this BlobBuilder, preparing for new appends to an empty Blob
	 */
	public void clear() {
		acc=Blob.EMPTY;
		tail=null;
		count=0;
	}
}
