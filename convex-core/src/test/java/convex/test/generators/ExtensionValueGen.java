package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.data.ExtensionValue;
import convex.core.data.Tag;

/**
 * Generator for CAD3 dense records
 *
 */
public class ExtensionValueGen extends AGenerator<ExtensionValue> {
	public ExtensionValueGen() {
		super(ExtensionValue.class);
	}

	@Override
	public ExtensionValue generate(SourceOfRandomness r, GenerationStatus status) {

		byte type = (byte)(r.nextInt(16)+Tag.EXTENSION_VALUE_BASE);
		long val=r.nextLong(0, 1+status.size());
		
		return ExtensionValue.create(type, val);
	}
}
