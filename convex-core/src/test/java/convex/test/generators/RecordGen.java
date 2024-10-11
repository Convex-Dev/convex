package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.cvm.AccountStatus;
import convex.core.cvm.PeerStatus;
import convex.core.cvm.State;
import convex.core.data.ARecord;
import convex.core.lang.TestState;

/**
 * Generator for records, will be CVM Values
 *
 */
public class RecordGen extends Generator<ARecord> {
	public RecordGen() {
		super(ARecord.class);
	}

	@Override
	public ARecord generate(SourceOfRandomness r, GenerationStatus status) {

		int type = r.nextInt();
		switch (type % 5) {
		case 0: {
			AccountStatus as=AccountStatus.create(r.nextLong(0, 1000000000000000000L),null);
			return as;
			}
		case 1: {
			PeerStatus ps=PeerStatus.create(null, r.nextLong(0, 1000000000000000000L));
			return ps;
			}
		case 2: {
			return TestState.STATE;
			}
		default:{
			return State.EMPTY;
			}
		}
	}
}
