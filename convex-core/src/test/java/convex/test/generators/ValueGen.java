package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.cvm.Syntax;
import convex.core.data.ACell;

/**
 * Generator for arbitrary CVM values
 */
public class ValueGen extends Generator<ACell> {
	public ValueGen() {
		super(ACell.class);
	}

	@Override
	public ACell generate(SourceOfRandomness r, GenerationStatus status) {
		int type = r.nextInt(15);
		switch (type) {
		case 0:
			return null;
		case 1:
			return Gen.PRIMITIVE.generate(r, status);
		case 2:
			return Gen.STRING.generate(r, status);
		case 3:
			return Gen.VECTOR.generate(r, status);
		case 4:
			return Gen.LIST.generate(r, status);
		case 5:
			return Gen.HASHMAP.generate(r, status);
		case 6:
			return Gen.SET.generate(r, status);
		case 7:
			return Gen.BLOB.generate(r, status);
		case 8:
			return Gen.ADDRESS.generate(r, status);
		case 9:
			return Gen.NUMERIC.generate(r, status);
		case 10:
			return Gen.LONG.generate(r, status);
		case 11:
			return Gen.SYMBOL.generate(r, status);
		case 12:
			return Gen.KEYWORD.generate(r, status);
		case 13:
			return Gen.RECORD.generate(r, status);
		case 14:
			return Syntax.create(generate(r, status));

		default:
			throw new Error("Unexpected type: " + type);
		}
	}
}
