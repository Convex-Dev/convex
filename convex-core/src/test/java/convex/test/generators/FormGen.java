package convex.test.generators;

import java.util.List;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.cvm.Syntax;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.Lists;
import convex.core.data.Symbol;
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

	@Override
	public ACell generate(SourceOfRandomness r, GenerationStatus status) {
		int type = r.nextInt(12);
		switch (type) {
			case 0:
				return null;
	
			case 1:
				// Empty syntax with a nested form
				return Syntax.create(generate(r, status));
	
			case 2:
				return Gen.PRIMITIVE.generate(r, status);
			case 3:
				return Gen.STRING.generate(r, status);
	
			case 4:
				return Gen.NUMERIC.generate(r, status);
	
			case 5: {
				// List of random forms
				List<ACell> subForms = this.times(r.nextInt(4)).generate(r, status);
				return Lists.create(subForms);
			}	
	
			case 6: {
				// random core symbol
				AHashMap<Symbol, ACell> env = Core.ENVIRONMENT;
				int n = (int) env.count();
				Symbol sym = env.entryAt(r.nextInt(n)).getKey();
				return sym;
			}
	
			case 7: {
				// a vector of random subforms
				List<ACell> subForms = this.times(r.nextInt(4)).generate(r, status);
				return Vectors.create(subForms);
			}
			
			case 8: {
				return Gen.SYNTAX.generate(r, status);
			}
	
			case 9:
				return Gen.VALUE.generate(r, status);
				
			default: {
				// random form containing a core symbol at head
				List<ACell> subForms = this.times(r.nextInt(4)).generate(r, status);
				AHashMap<Symbol, ACell> env = Core.ENVIRONMENT;
				int n = (int) env.count();
				Symbol sym = env.entryAt(r.nextInt(n)).getKey();
				return RT.cons(sym, Lists.create(subForms));
			}
		}	
	}
}
