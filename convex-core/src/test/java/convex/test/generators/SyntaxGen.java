package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.cvm.Syntax;
import convex.core.data.ACell;
import convex.core.data.AHashMap;

/**
 * Generator for CAD3 dense records
 *
 */
public class SyntaxGen extends AGenerator<Syntax> {
	public SyntaxGen() {
		super(Syntax.class);
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Override
	public Syntax generate(SourceOfRandomness r, GenerationStatus status) {
		
		AHashMap meta=r.nextBoolean()?null:Gen.HASHMAP.generate(r, status);
		ACell value=Gen.VALUE.generate(r, status);
		
		return Syntax.create(value,meta);
	}
}
