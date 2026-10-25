package convex.auth.jwt;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

/** Shared read-only keys: JWT/JWKS tests exercise signatures and parsing, not RSA key generation. */
final class RSAFixtures {
	static final KeyPair KEY=create();
	static final KeyPair OTHER_KEY=create();

	private RSAFixtures() {}

	private static KeyPair create() {
		try {
			KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			return generator.generateKeyPair();
		} catch (GeneralSecurityException e) {
			throw new ExceptionInInitializerError(e);
		}
	}
}
