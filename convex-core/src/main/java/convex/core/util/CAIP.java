package convex.core.util;

import convex.core.data.AString;
import convex.core.data.Strings;

public class CAIP {
	/**
	 * CAIP19 Asset ID using the "slip44" namespace as conventional for native coins 
	 */
	public static AString CONVEX_ASSET_ID=Strings.intern("slip44:864");
	
	/**
	 * Asset namespace for CAIP19, eg. as used in "cad29:6786"
	 */
	public static AString CAD29_ASSET_NAMESPACE=Strings.intern("cad29");
}
