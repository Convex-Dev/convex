package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.data.ACell;
import convex.core.data.Symbol;
import convex.core.data.Syntax;
import convex.core.data.prim.CVMLong;

/**
 * Generator for arbitrary values
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
			return gen().make(PrimitiveGen.class).generate(r, status);
		case 2:
			return gen().make(StringGen.class).generate(r, status);
		case 3:
			return gen().make(VectorGen.class).generate(r, status);
		case 4:
			return gen().make(ListGen.class).generate(r, status);
		case 5:
			return gen().make(HashMapGen.class).generate(r, status);
		case 6:
			return gen().make(SetGen.class).generate(r, status);
		case 7:
			return Gen.BLOB.generate(r, status);
		case 8:
			return Gen.ADDRESS.generate(r, status);
		case 9:
			return Gen.NUMERIC.generate(r, status);
		case 10:
			return CVMLong.create(r.nextLong());
		case 11:
			return Symbol.create("sym" + gen().type(Long.class).generate(r, status));
		case 12:
			return gen().make(KeywordGen.class).generate(r, status);
		case 13:
			return gen().make(RecordGen.class).generate(r, status);
		case 14:
			return Syntax.create(generate(r, status));

		default:
			throw new Error("Unexpected type: " + type);
		}
	}
}
