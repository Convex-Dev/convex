package convex.test.generators;

import java.security.SecureRandom;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.crypto.ECDSAKeyPair;
import convex.core.data.Address;

public class AddressGen extends Generator<Address> {
	public AddressGen() {
		super(Address.class);
	}

	@Override
	public Address generate(SourceOfRandomness r, GenerationStatus status) {

		ECDSAKeyPair kp = ECDSAKeyPair.generate(new SecureRandom());
		return Address.fromPublicKey(kp.getPublicKey());
	}
}
