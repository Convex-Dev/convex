package convex.test.generators;

import java.util.List;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.data.Lists;
import convex.core.data.Symbol;
import convex.core.data.Syntax;
import convex.core.data.Vectors;
import convex.core.lang.Core;
import convex.core.lang.RT;

/**
 * Generator for plausible forms
 */
public class FormGen extends Generator<Object> {
	public FormGen() {
		super(Object.class);
	}

	@Override
	public Object generate(SourceOfRandomness r, GenerationStatus status) {
		int type = r.nextInt(8);
		switch (type) {
		case 0:
			return null;

		case 1:
			return Syntax.create(generate(r, status));

		case 2:
			return gen().make(PrimitiveGen.class).generate(r, status);
		case 3:
			return gen().type(String.class).generate(r, status);

		case 4:
			return gen().make(NumericGen.class).generate(r, status);

		case 5: {
			// random form containing core symbol at head
			List<Object> subForms = this.times(r.nextInt(4)).generate(r, status);
			int n = (int) Core.ENVIRONMENT.count();
			Symbol sym = Core.ENVIRONMENT.entryAt(r.nextInt(n)).getKey();
			return RT.cons(sym, Lists.create(subForms));
		}

		case 6: {
			// random core symbol
			int n = (int) Core.ENVIRONMENT.count();
			Symbol sym = Core.ENVIRONMENT.entryAt(r.nextInt(n)).getKey();
			return sym;
		}

		case 7: {
			List<Object> subForms = this.times(r.nextInt(4)).generate(r, status);
			return Vectors.create(subForms);
		}

		default:
			throw new Error("Unexpected type: " + type);
		}
	}
}
