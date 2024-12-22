package convex.test.generators;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.data.ABlob;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.Cells;
import convex.core.data.impl.LongBlob;
import convex.test.Samples;

/**
 * Generator for binary Blobs
 *
 */
public class BlobGen extends AGenerator<ABlob> {
	public BlobGen() {
		super(ABlob.class);
	}

	@Override
	public ABlob generate(SourceOfRandomness r, GenerationStatus status) {

		long len = status.size();
		int type = r.nextInt();
		switch (type % 10) {
		case 0:
			return Blob.EMPTY;
		case 1:
			return LongBlob.create(r.nextLong(0,len));
		case 2:
			return Samples.FULL_BLOB;
		case 3:
			return Samples.BIG_BLOB_TREE;
		case 4:
			return Samples.MAX_EMBEDDED_BLOB;
		case 5:
			return Samples.NON_EMBEDDED_BLOB;
		case 6:
			return Cells.encode(Gen.PRIMITIVE.generate(r, status));
		case 7: {
			// use a slice from a big blob
			long length=Math.min(len, Samples.BIG_BLOB_LENGTH);
			length=r.nextLong(0, length);
			long start=r.nextLong(0,Samples.BIG_BLOB_LENGTH-length);
			return Samples.BIG_BLOB_TREE.slice(start,start+length).getCanonical();
		}
		default:
			return Blobs.createRandom(r.toJDKRandom(), len);
		}
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public List<ABlob> doShrink(SourceOfRandomness r, ABlob s) {
		long n=s.count();
		if (n==0) return Collections.EMPTY_LIST;
		if (n==1) return Collections.singletonList(Blobs.empty());
		ArrayList<ABlob> al=new ArrayList<>();
		
		al.add(s.slice(0, n/2)); // first half
		al.add(s.slice(n/2, n)); // second half
		if (n>2) {
			al.add(s.slice(n/3, (2*n)/3)); // middle 3rd
			al.add(s.slice(0, n-1)); // all except last char
		}
		Collections.sort(al,Cells.countComparator);
		return al;
	}
}
