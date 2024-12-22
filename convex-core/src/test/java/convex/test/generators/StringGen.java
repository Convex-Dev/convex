package convex.test.generators;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

import convex.core.cvm.Symbols;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.test.Samples;

/**
 * Generator for valid UTF-8 strings
 */
public class StringGen extends Generator<AString> {
	public StringGen() {
		super(AString.class);
	}

	@Override
	public AString generate(SourceOfRandomness r, GenerationStatus status) {
		int size=status.size();
		
    	switch (r.nextInt(10)) {
    		case 0: return Strings.empty();
    		case 1: return Samples.MAX_EMBEDDED_STRING;
    		case 2: return Samples.NON_EMBEDDED_STRING;
    		case 3: return Samples.MAX_SHORT_STRING;
       		case 4: return Samples.MIN_TREE_STRING;
       		case 5: return Samples.RUSSIAN_STRING;
       		case 6: return generate(r,status).append(generate(r,status));
       		case 7: return Symbols.FOO.getName();
       		case 8: return Strings.create(Gen.CHAR.generate(r, status));
     	    		
    		default: {
    			AString BASE=generate(r,status);
    			long n=Math.min(size,BASE.count());
    			long start=r.nextLong(0,n);
    			long end=r.nextLong(start,n);
    			if ((start==n)||(BASE.charAt(start)<0)||((BASE.charAt(end)<0))) return BASE;
       		    return BASE.slice(start,end);
    		}
    	}
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public List<AString> doShrink(SourceOfRandomness r, AString s) {
		long n=s.count();
		if (n==0) return Collections.EMPTY_LIST;
		if (n==1) return Collections.singletonList(Strings.EMPTY);
		ArrayList<AString> al=new ArrayList<>();
		
		al.add(s.slice(0, n/2)); // first half
		al.add(s.slice(n/2, n)); // second half
		al.add(s.slice(n/3, (2*n)/3)); // middle 3rd
		al.add(s.slice(0, n-1)); // all except last char
		
		Collections.sort(al,Strings.lengthComparator);
		return al;
	}
}
