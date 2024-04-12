package examples.generators;

import java.util.ArrayList;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

/**
 * Generator for arbitrary trees of Longs
 */
public class BadListGen extends Generator<Object> {
	public BadListGen() {
		super(Object.class);
	}

	@Override
	public Object generate(SourceOfRandomness r, GenerationStatus status) {
		int size=status.size()+1;
		int type = r.nextInt(10);
		
		switch (type) {
			case 0: 
				int n = r.nextInt(size);
				ArrayList<Object> al=new ArrayList<>();
				for (int i=0; i<n; i++) {
					al.add(generate(r,status));
				}
				return al;
				
			default: 
				Long l=r.nextLong(-size, size);
				return l;
		}
	}
}
