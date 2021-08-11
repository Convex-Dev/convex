package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.Constants;
import convex.core.data.Address;
import convex.core.data.Vectors;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Invoke;
import convex.core.transactions.Transfer;
import convex.test.Samples;

public class TransactionGen extends Generator<ATransaction> {
	public TransactionGen() {
		super(ATransaction.class);
	}

	@Override
	public ATransaction generate(SourceOfRandomness r, GenerationStatus status) {

		long amt = r.nextLong(0, Constants.MAX_SUPPLY);

		Address src = Address.create(Samples.KEY_PAIR.getAccountKey());
		long seq = r.nextInt(10000);
		int type = r.nextInt(2);
		switch (type) {
		case 0: {
			return Transfer.create(src,seq, src, amt);
		}
		case 1: {
			return Invoke.create(src,seq, Vectors.empty());
		}
		default:
			throw new Error("Invalid type: " + type);
		}
	}
}
