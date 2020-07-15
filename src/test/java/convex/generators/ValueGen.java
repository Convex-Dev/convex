package convex.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.data.Symbol;
import convex.core.data.Syntax;

/**
 * Generator for arbitrary values
 */
public class ValueGen extends Generator<Object> {
	public ValueGen() {
		super(Object.class);
	}

	@Override
	public Object generate(SourceOfRandomness r, GenerationStatus status) {
		int type = r.nextInt(15);
		switch (type) {
		case 0:
			return null;
		case 1:
			return gen().make(PrimitiveGen.class).generate(r, status);
		case 2:
			return gen().type(String.class).generate(r, status);
		case 3:
			return gen().make(VectorGen.class).generate(r, status);
		case 4:
			return gen().make(ListGen.class).generate(r, status);
		case 5:
			return gen().make(MapGen.class).generate(r, status);
		case 6:
			return gen().make(SetGen.class).generate(r, status);
		case 7:
			return gen().make(BlobGen.class).generate(r, status);
		case 8:
			return gen().make(AddressGen.class).generate(r, status);
		case 9:
			return gen().make(NumericGen.class).generate(r, status);
		case 10:
			return gen().make(AmountGen.class).generate(r, status);
		case 11:
			return Symbol.create("sym" + gen().type(Long.class).generate(r, status).toString());
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
