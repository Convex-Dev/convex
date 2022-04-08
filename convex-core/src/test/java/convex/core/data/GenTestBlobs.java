package convex.core.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.runner.RunWith;

import com.pholser.junit.quickcheck.From;
import com.pholser.junit.quickcheck.Property;
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck;

import convex.core.util.Utils;
import convex.test.generators.BlobGen;

@RunWith(JUnitQuickcheck.class)
public class GenTestBlobs {

	@Property
	public void testToLong(@From(BlobGen.class) ABlob blob) {
		long len=blob.count();
		long lv=blob.toLong();

		int slen=Math.min(8,Utils.checkedInt(len));
		assertEquals(lv,blob.slice(len-slen,len).toLong());
	}
}
