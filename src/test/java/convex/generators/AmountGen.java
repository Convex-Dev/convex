package convex.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.data.Amount;

/**
 * Generator for Amount objects
 * 
 */
public class AmountGen extends Generator<Amount> {
	public AmountGen() {
		super(Amount.class);
	}

	@Override
	public Amount generate(SourceOfRandomness r, GenerationStatus status) {

		int type = r.nextInt(3);
		switch (type) {
		case 0: {
			return Amount.create(0);
		}
		case 1: {
			return Amount.create(r.nextLong(0, Math.min(Amount.MAX_AMOUNT, status.size())));
		}
		case 2: {
			return Amount.create(r.nextLong(0, Amount.MAX_AMOUNT));
		}
		default:
			throw new Error("Invalid type: " + type);
		}
	}
}
