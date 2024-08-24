package convex.core.examples;

import convex.core.data.AVector;
import convex.core.data.Cells;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;

public class EncodingTest {

	public static void main(String... args) {
		AVector<CVMLong> v=Vectors.empty();
		
		for (int i=0; i<256; i++) {
			v=v.conj(CVMLong.create(i));
		}
		
		System.out.println(v.toString());
		System.out.println("Complete encoded: "+Cells.isCompletelyEncoded(v));
		System.out.println("Embedded: "+v.isEmbedded());
		System.out.println("Encoding Length: "+v.getEncodingLength());
	}
}
