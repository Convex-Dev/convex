package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import convex.core.data.Address;

public class AddressGen extends Generator<Address> {
	public AddressGen() {
		super(Address.class);
	}

	@Override
	public Address generate(SourceOfRandomness r, GenerationStatus status) {

		return Address.create(r.nextLong(0, Long.MAX_VALUE));
	}
}
