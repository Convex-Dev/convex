package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.data.ACell;
import convex.core.data.CodedValue;
import convex.core.data.Tag;

/**
 * Generator for CAD3 dense records
 *
 */
public class CodedValueGen extends AGenerator<CodedValue> {
	public CodedValueGen() {
		super(CodedValue.class);
	}

	@Override
	public CodedValue generate(SourceOfRandomness r, GenerationStatus status) {

		byte type = (byte)(r.nextInt(16)+Tag.CODE_BASE);
		
		ACell code=r.nextBoolean()?Gen.BYTE_FLAG.generate(r, status):Gen.VALUE.generate(r, status);
		ACell value=Gen.VALUE.generate(r, status);
		
		return CodedValue.create(type, code, value);
	}
}
