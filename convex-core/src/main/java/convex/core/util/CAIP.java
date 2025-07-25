package convex.core.util;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.exceptions.TODOException;

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
	 */
	public static ACell parseAssetID(AString assetID) {
		if (assetID.startsWith(CAD29_ASSET_NAMESPACE)) {
			throw new TODOException();
		} else {
			throw new IllegalArgumentException("Unrecognised CAIP19 asset format");
		}
	}
}
