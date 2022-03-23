package convex.core.lang.impl;

import convex.core.data.ABlob;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Strings;
import convex.core.data.prim.CVMChar;
import convex.core.util.Utils;

/**
 * Similar to Java StringBuilder designed for adding small Blobs
 */
public class BlobBuilder {

	protected ABlob acc=Blob.EMPTY; // Always a canonical Blob with filled chunks
	protected byte[] tail=null;
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
	
	public void append(AString a) {
		append(a.toBlob());
	}
	
	public void append(ABlob b) {
		long blen=b.count();
		if (blen==0) return; // nothing to do
		
		long spare=spare();
		
		if (blen<=spare) {
			// Does not overflow current chunk
			ensureArray(arrayPos()+blen);
			b.getBytes(tail,arrayPos());
			count+=blen;
			if (blen==spare) completeChunk();
			return;
		} else {
			// append to fill current chunk
			append(b.slice(0, spare));
			
			// Index over offset into b
			for (long off=spare; off<blen; off+=Blob.CHUNK_LENGTH) {
				long take=Math.min(Blob.CHUNK_LENGTH, blen-off);
				append(b.slice(off,take));
			}
			return;
		}
	}

	/**
	 * Completes a chunk to the accumulated Blob and clears the tail
	 */
	private void completeChunk() {
		if (tail.length!=Blob.CHUNK_LENGTH) throw new Error("tail not complete! Has length: "+tail.length);
		Blob b=Blob.wrap(tail);
		acc=acc.append(b);
		tail=null;
	}

	/**
	 * Ensures array length at least equal to n
	 * @param n
	 */
	private void ensureArray(long n) {
		if (n>Blob.CHUNK_LENGTH) throw new Error("Invalid array size request: "+n);
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
	
	public ABlob toBlob() {
		ABlob result=acc;
		if (tail==null) return acc;
		
		result=result.append(Blob.wrap(tail,0,arrayPos()));	
		if (result.count()!=count) throw new Error("Invalid count!!");
		return result;
	}
	
	public AString getCVMString() {
		ABlob result=toBlob();
		return Strings.create(result);
	}

	public long count() {
		return count;
	}

	public void append(String string) {
		append(Strings.create(string));
	}
	
	public void append(byte b) {
		int spare=spare();
		if (spare<1) throw new Error("BlobBuilder should always have spare bytes but was: "+spare);
		ensureArray(arrayPos()+1);
		tail[Blob.CHUNK_LENGTH-spare]=b;
		count+=1;
		if (spare==1) {
			completeChunk();
		}
	}
	
	public void append(char c) {
		if (c<128) {
			append((byte)c);
		} else {
			append(CVMChar.create(c));
		}
	}

	private void append(CVMChar c) {
		append(c.toCVMString(count));
	}

	/**
	 * Checks if this BlobBuilder is less than or equal to the specified limit
	 * @param limit Maximum allowable length of BlobBuilder
	 * @return true if within limit, false if exceeded
	 */
	public boolean check(long limit) {
		return count<=limit;
	}
}
