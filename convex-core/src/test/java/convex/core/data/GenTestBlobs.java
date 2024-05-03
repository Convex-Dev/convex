package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.runner.RunWith;

import com.pholser.junit.quickcheck.From;
import com.pholser.junit.quickcheck.Property;
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck;

import convex.core.data.util.BlobBuilder;
import convex.core.util.Utils;
import convex.test.generators.BlobGen;

@RunWith(JUnitQuickcheck.class)
public class GenTestBlobs {

	@Property
	public void testToLong(@From(BlobGen.class) ABlob blob) {
		long len=blob.count();
		long lv=blob.longValue();

		int slen=Math.min(8,Utils.checkedInt(len));
		assertEquals(lv,blob.slice(len-slen,len).longValue());
	}
	
	@Property
	public void testBlobSlicing(Long size, Long off, Long len) {
		size=Math.floorMod(size, 100000L);
		
		off=(size>0)?Math.floorMod(off, size):0;
		len=((size-off)>0)?Math.floorMod(len, size-off):0;
		
		ABlob full=Blobs.createRandom(size).toCanonical();
		
		ABlob head=full.slice(0,off).toCanonical();
		ABlob slice=full.slice(off, off+len).toCanonical();
		ABlob tail=full.slice(off+len, size).toCanonical();
		
		// Replacing with same slice should work
		assertEquals(len,slice.count());
		ABlob rep=full.replaceSlice(off, slice);
		assertEquals(full,rep);
		
		// Reassembling pieces should work
		BlobBuilder bb=new BlobBuilder();
		bb.append(head);
		bb.append(slice);
		bb.append(tail);
		assertEquals(full,bb.toBlob());
	}
}
