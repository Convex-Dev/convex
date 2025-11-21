package convex.core.util;

import convex.core.cvm.Address;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.exceptions.ParseException;
import convex.core.lang.RT;
import convex.core.lang.Reader;

/**
 * Utility class for Chain Agnostic Improvement Proposals (CAIPs) 
 * 
 * These describe standards for DLT projects that are not specific to a single chain.
 * 
 * See: https://chainagnostic.org/
 */
public class CAIP {
	/**
	 * CAIP19 Asset ID using the "slip44" namespace as conventional for native coins 
	 */
	public static AString CONVEX_ASSET_ID=Strings.intern("slip44:864");
	
	/**
	 * Asset namespace for CAD29 assets in CAIP-19, e.g. as used in "cad29:6786"
	 */
	public static AString CAD29_ASSET_NAMESPACE=Strings.intern("cad29");
	
	private static final String CAD29_PREFIX="cad29:";
	
	/**
	 * CAIP2 Chain ID for Protonet 
	 */
	public static AString PROTONET=Strings.intern("convex:protonet");
	
	/**
	 * CAIP2 Chain ID for local test networks 
	 */
	public static AString LOCALNET=Strings.intern("convex:local");

	/**
	 * CAIP2 Chain ID for Testnet 
	 */
	public static AString TESTNET=Strings.intern("convex:testnet");

	/**
	 * CAIP2 Chain ID for MainNet 
	 */
	public static AString MAINNET=Strings.intern("convex:main");

	
	/**
	 * Parse a CAIP-19 asset ID to an on-chain CVM asset ID usable with convex.asset
	 * @param assetID CAIP-19 asset ID
	 * @return Convex asset representation
	 * @throws IllegalArgumentException If asset ID not recognised
	 */
	public static ACell parseAssetID(AString assetID) {
		if (assetID==null) throw new IllegalArgumentException("Null asset ID");
		if (assetID.equals(CONVEX_ASSET_ID)) return CONVEX_ASSET_ID;
		if (assetID.startsWith(CAD29_ASSET_NAMESPACE)) {
			return parseTokenID(assetID.toString());
		} else {
			throw new IllegalArgumentException("Unrecognised CAIP19 asset format: "+assetID);
		}
	}
	
	/**
	 * Gets the asset ID for a CAD29 token from a CAPI-19 string
	 * @param caip19 CAIP19 Format asset ID, e.g. "cad29:72"
	 * @return CAD29 asset ID, or CONVEX_ASSET_ID constant if the String refers to CVM
	 * @throws IllegalArgumentException if String is not a valid token ID
	 */
	public static ACell parseTokenID(String caip19) {
		if (isCVM(caip19)) return CONVEX_ASSET_ID;
		String[] ss=caip19.split(":");
		if (ss[0].equals("cad29")) try {
			String id=ss[1];
			ACell assetID;
			int dashPos=id.indexOf("-");
			if (dashPos<0) {
				// single value, must be an address
				assetID=Address.parse(id);
				if (assetID==null) {
					throw new IllegalArgumentException("Invalid address for CAD29 asset: "+id);
				}
			} else {
				// dashed value, interpret as vector
				ACell addr=assetID=Address.parse(id.substring(0, dashPos));
				if (addr==null) {
					throw new IllegalArgumentException("Invalid address for scoped CAD29 asset: "+id);
				}
				ACell scope=Reader.read(Utils.urlDecode(id.substring(dashPos+1)));
				assetID=Vectors.create(addr,scope);
			}
			return assetID;
		} catch (ParseException | IndexOutOfBoundsException e) {
			throw new IllegalArgumentException("Invalid CAIP19 asset for Convex: "+caip19,e);
		}
		throw new IllegalArgumentException("Only CVM or CAD29 assets currently supported on Convex, but CAIP19 code was: "+caip19);
	}
	
	/**
	 * Converts a Convex CAD29 asset ID to CAIP-19 format
	 * @param assetID CAD29 Asset ID
	 * @return CAIP19 ID for a Convex token
	 */
	public static String toAssetID(ACell assetID) {
		if (assetID instanceof Address addr) {
			return CAD29_PREFIX+Long.toString(addr.longValue());
		} else if (assetID instanceof AVector v) {
			if (v.count()!=2) throw new IllegalArgumentException("Not a valid CAD29 scoped token ID: 2 elements requires");
			return toAssetID(v.get(0))+"-"+Utils.urlEncode(RT.print(v.get(1)).toString());
		}
		throw new IllegalArgumentException("Not a valid CAD29 token ID: "+assetID);
	}
	
	/**
	 * Tests if the capi19 asset ID string String refers to the CVM native coin 
	 * @param caip19 CAIP-19 asset ID String e.g. "slip44:864"
	 * @return true if the asset is the CVM asset in CAIP19 format
	 */
	public static boolean isCVM(String caip19) {
		return "slip44:864".equals(caip19);
	}

}
