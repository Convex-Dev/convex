package convex.core.crypto;

import java.security.Provider;
import java.security.SecureRandom;
import java.security.SecureRandomSpi;
import java.util.Arrays;

/**
 * A SecureRandom instance that returns deterministic values given an initial seed.
 * 
 * SECURITY: Neither secure nor random, but useful for testing. Please don't use for protecting real assets....
 * 
 */
@SuppressWarnings("serial")
public class InsecureRandom extends SecureRandom {

	public InsecureRandom(long seed) {
		super(new InsecureRandomSpi(seed), SECURITY_PROVIDER);
	}
	
	private static final Provider SECURITY_PROVIDER = new InsecureRandomProvider();

	/**
	 * Security provider instance used to register this random provider.
	 */
	private static class InsecureRandomProvider extends Provider {
		private InsecureRandomProvider() {
			super("InsecureRandom","0.01","Random number generator with deterministic values");
		}
	}
	
	/**
	 * Actual work done with a SPI that extends java.security.SecureRandomSpi
	 */
	private static class InsecureRandomSpi extends SecureRandomSpi {
		private long seed;
		
		private InsecureRandomSpi(long seed) {
			this.seed=seed;
		}
		
		private static long nextLong(long x) {
		  // This is a basic XORShift PRNG
		  x ^= (x << 21);
		  x ^= (x >>> 35);
		  x ^= (x << 4);
		  return x;
		}

		private void initialise(byte[] seedBytes) {
			this.seed=nextLong(Arrays.hashCode(seedBytes));
		}

		@Override
		protected void engineSetSeed(byte[] seedBytes) {
			initialise(seedBytes);
		}

		@Override
		protected void engineNextBytes(byte[] out) {
			int n=out.length;
			long x=seed;
			for (int i=0; i<n; i++) {
				out[i]=(byte)(x>>32);
				x=nextLong(x);
			}
			seed=x;
		}

		@Override
		protected byte[] engineGenerateSeed(int length) {
			byte[] newSeed = new byte[length];
			engineNextBytes(newSeed);
			return newSeed;
		}
	}


}
