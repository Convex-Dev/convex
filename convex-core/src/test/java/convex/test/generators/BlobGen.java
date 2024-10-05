package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.data.ABlob;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.Format;
import convex.core.data.impl.LongBlob;
import convex.test.Samples;

/**
 * Generator for binary Blobs
 *
 */
public class BlobGen extends Generator<ABlob> {
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
			return Format.encodedBlob(Gen.PRIMITIVE.generate(r, status));
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
}
