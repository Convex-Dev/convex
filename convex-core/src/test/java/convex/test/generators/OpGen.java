package convex.test.generators;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.cvm.AOp;
import convex.core.cvm.ops.Cond;
import convex.core.cvm.ops.Constant;
import convex.core.cvm.ops.Do;
import convex.core.cvm.ops.Invoke;
import convex.core.cvm.ops.Local;
import convex.core.cvm.ops.Query;
import convex.core.cvm.ops.Special;

/**
 * Generator for plausible forms
 */
@SuppressWarnings("rawtypes")
public class OpGen extends Generator<AOp> {
	public OpGen() {
		super(AOp.class);
	}

	@Override
	public AOp generate(SourceOfRandomness r, GenerationStatus status) {
		int size=status.size();
		
		switch (r.nextInt(8)) {
			case 0: {
				// flat multi-ops supporting any ops as children
				int n=(int) (Math.sqrt(r.nextInt(1+size)));
				AOp[] ops=new AOp[n];
				for (int i=0; i<n; i++) {
					ops[i]=Gen.OP.generate(r,status);
				}
				switch(r.nextInt(4)) {
					case 0: return Do.create(ops);
					case 1: return Cond.create(ops);
					case 2: return Invoke.create(ops);
					case 3: return Query.create(ops);
				}
			}
			
			case 1:
				return Special.create(r.nextInt(Special.BASE, Special.BASE+Special.NUM_SPECIALS-1));
				
			case 2:
				return Local.create(r.nextInt(1+size));
				
			default:
				return Constant.of(Gen.VALUE.generate(r, status));
		}
	}
}
