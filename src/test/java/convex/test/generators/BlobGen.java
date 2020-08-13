package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.data.ABlob;
import convex.core.data.Blobs;
import convex.core.data.LongBlob;
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
		switch (type % 8) {
		case 0:
			return LongBlob.create(r.nextLong());
		case 1:
			return Samples.FULL_BLOB;
		case 2:
			return Samples.BIG_BLOB_TREE;
		case 3: {
			// use a slice from a big blob
			long length=Math.min(len, Samples.BIG_BLOB_LENGTH);
			length=r.nextLong(0, length);
			long start=r.nextLong(0,Samples.BIG_BLOB_LENGTH-length);
			return Samples.BIG_BLOB_TREE.slice(start,length);
		}
		default:
			return Blobs.createRandom(r.toJDKRandom(), len);
		}
	}
}
