package convex.test.generators;

import java.security.SecureRandom;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.Ed25519KeyPair;
import convex.core.data.Address;

public class AddressGen extends Generator<Address> {
	public AddressGen() {
		super(Address.class);
	}

	@Override
	public Address generate(SourceOfRandomness r, GenerationStatus status) {

		AKeyPair kp = Ed25519KeyPair.generate(new SecureRandom());
		// TODO: numeric addresses
		return Address.create(kp.getAccountKey());
	}
}
