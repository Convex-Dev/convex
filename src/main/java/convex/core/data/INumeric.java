package convex.core.data;

import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;

public interface INumeric {

	
	public CVMLong toLong();
	
	public CVMDouble toDouble();

	public double doubleValue();
	
}
