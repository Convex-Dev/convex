package convex.test.generators;

import java.util.List;
import java.util.Map;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Lists;
import convex.core.data.Strings;
import convex.core.data.Symbol;
import convex.core.data.Syntax;
import convex.core.data.Vectors;
import convex.core.lang.Core;
import convex.core.lang.RT;

/**
 * Generator for plausible forms
 */
public class FormGen extends Generator<ACell> {
	public FormGen() {
		super(ACell.class);
	}
	
	private static ACell[] coreVals=Core.getMap().values().toArray(new ACell[0]);
	
	private static Symbol randomCoreSymbol(SourceOfRandomness r) {
		int n = coreVals.length;
		return Core.symbolFor(coreVals[r.nextInt(n)]);
	}

	@Override
	public ACell generate(SourceOfRandomness r, GenerationStatus status) {
		int type = r.nextInt(8);
		switch (type) {
		case 0:
			return null;

		case 1:
			return Syntax.create(generate(r, status));

		case 2:
			return gen().make(PrimitiveGen.class).generate(r, status);
		case 3:
			return Strings.create(gen().type(String.class).generate(r, status));

		case 4:
			return gen().make(NumericGen.class).generate(r, status);

		case 5: {
			// random form containing core symbol at head
			List<ACell> subForms = this.times(r.nextInt(4)).generate(r, status);
		
			Symbol sym = randomCoreSymbol(r);
			return RT.cons(sym, Lists.create(subForms));
		}

		case 6: {
			// random core symbol
			Symbol sym = randomCoreSymbol(r);
			return sym;
		}

		case 7: {
			// a vector of random subforms
			List<ACell> subForms = this.times(r.nextInt(4)).generate(r, status);
			return Vectors.create(subForms);
		}

		default:
			throw new Error("Unexpected type: " + type);
		}
	}
}
