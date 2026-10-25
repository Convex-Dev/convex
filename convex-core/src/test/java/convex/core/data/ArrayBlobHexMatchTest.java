package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;

import org.junit.jupiter.api.Test;

import convex.core.data.impl.LongBlob;

class ArrayBlobHexMatchTest {

	@Test void rangedMatchingAgreesWithNibbleComparisonIncludingOffsetsAndPartialBytes() {
		byte[] original=new byte[8];
		new Random(123).nextBytes(original);
		for (int leftOffset:new int[] {0,3}) {
			byte[] left=new byte[leftOffset+8];
			System.arraycopy(original,0,left,leftOffset,8);
			Blob a=Blob.wrap(left,leftOffset,8);
			for (int difference=-1;difference<16;difference++) {
				byte[] right=new byte[13];
				System.arraycopy(original,0,right,5,8);
				if (difference>=0) right[5+(difference>>>1)]^=(byte)((difference&1)==0?0x10:0x01);
				Blob b=Blob.wrap(right,5,8);
				for (ABlobLike<?> other:new ABlobLike<?>[] {a,b,LongBlob.create(b.longValue())}) {
					for (int start=0;start<=16;start++) {
						for (int end=start;end<=16;end++) {
							long expected=0;
							while (start+expected<end && a.getHexDigit(start+expected)==other.getHexDigit(start+expected)) expected++;
							assertEquals(expected,a.hexMatch(other,start,end-start));
						}
					}
				}
			}
		}
	}
}
