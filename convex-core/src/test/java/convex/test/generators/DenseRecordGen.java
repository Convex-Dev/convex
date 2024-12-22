package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.DenseRecord;
import convex.core.data.Tag;

/**
 * Generator for CAD3 dense records
 *
 */
public class DenseRecordGen extends AGenerator<DenseRecord> {
	public DenseRecordGen() {
		super(DenseRecord.class);
	}

	@SuppressWarnings("unchecked")
	@Override
	public DenseRecord generate(SourceOfRandomness r, GenerationStatus status) {

		int type = r.nextInt(16)+Tag.DENSE_RECORD_BASE;
		AVector<ACell> v=Gen.VECTOR.generate(r, status);
		
		return DenseRecord.create(type, v);
	}
}
