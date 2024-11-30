package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.cvm.Address;

/**
 * Generator for arbitrary Addresses
 */
public class AddressGen extends Generator<Address> {
	
	public AddressGen() {
		super(Address.class);
	}

	@Override
	public Address generate(SourceOfRandomness r, GenerationStatus status) {
		int size=status.size();
		int x=r.nextInt(10);
		
		switch (x) {
			case 0: return Address.ZERO;
			case 1: return Address.create(Long.MAX_VALUE>>r.nextInt(64));
			case 2: return Address.create(10*r.nextLong(0, size));
			default: return Address.create(r.nextLong(0, size));
		}
	}
}
