package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.data.AString;
import convex.core.data.Keyword;
import convex.core.data.Symbol;
import convex.core.data.Symbols;

/**
 * Generator for Symbol objects
 * 
 */
public class SymbolGen extends Generator<Symbol> {
	public SymbolGen() {
		super(Symbol.class);
	}

	@Override
	public Symbol generate(SourceOfRandomness r, GenerationStatus status) {
		int size=status.size();
		int type = r.nextInt(5);
		switch (type) {
		case 0:
			return Symbols.FOO;
		case 1:
			return Symbols.BAR;
		case 2: 
			AString name=Gen.STRING.generate(r, status);
			if ((name.count()>0)&&(name.count()<=Keyword.MAX_CHARS)) {
				return Symbol.create(name);
			}
			// fallthorugh!
		default: {
				return Symbol.create("sym" + r.nextLong(0, size));
			}
		}
	}
}
