package convex.gui.wallet;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;

import convex.api.Convex;
import convex.core.Result;
import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.data.Cells;
import convex.core.data.Keyword;
import convex.core.data.Vectors;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.Utils;

/**
 * Class representing a tradable / transferrable fungible token
 */
public class TokenInfo {
	private ACell id;
	private int decimals;
	private String symbol="???";

	private TokenInfo(ACell tokenID) {
		this.id=tokenID;
		this.decimals=(id==null)?9:2;
	}

	public ACell getID() {
		return id;
	}
	
	public String getSymbol() {
		return (id==null)?"CVM":symbol;
	}
	
	public int decimals() {
		return decimals;
	}

	public static TokenInfo convexCoin() {
		return forID(null);
	}
	
	public static TokenInfo forID(ACell tokenID) {
		TokenInfo tokenInfo=new TokenInfo(tokenID);
		return tokenInfo;
	}
	
	@Override
	public boolean equals(Object a) {
		if (a instanceof TokenInfo) {
			return Cells.equals(id, ((TokenInfo)a).id);
		} else {
			return false;
		}
	}
	
	public CompletableFuture<AInteger> getBalance(Convex convex) {
		String query=(id==null)?"*balance*":"("+getFungibleAddress(convex)+"/balance "+id+")";
		
		CompletableFuture<AInteger> cf=convex.query(query).thenApply(r->{
			if (!r.isError()) {
				ACell val=r.getValue();
				if (val instanceof AInteger) {
					return (AInteger) val;
				}
			}
			throw new RuntimeException("Error querying token balance: "+r);
		});
		return cf;
	}

	static Address fungibleAddress=null;
	public static Address getFungibleAddress(Convex convex) {
		if (fungibleAddress!=null) return fungibleAddress;
		try {
			fungibleAddress=(Address) convex.resolve("convex.fungible").get();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return fungibleAddress;
	}
	
	static Address multiAddress=null;
	public static Address getMultiAddress(Convex convex) {
		if (multiAddress!=null) return multiAddress;
		try {
			multiAddress=(Address) convex.resolve("asset.multi-token").get();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return multiAddress;
	}
	
	static Address torusAddress=null;
	public static Address getTorusAddress(Convex convex) {
		if (torusAddress!=null) return torusAddress;
		try {
			torusAddress=(Address) convex.resolve("torus.exchange").get();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return torusAddress;
	}

	public static TokenInfo get(Convex convex, ACell tokenID) {
		TokenInfo tokenInfo=new TokenInfo(tokenID);
		if (tokenID==null) return tokenInfo; // Convex coins
		
		// We need to get token info
		try {
			Result r=convex.querySync("("+getFungibleAddress(convex)+"/decimals "+tokenID+")");
			if (r.isError()) {
				System.err.println("Dubious Token: "+r.toString());
				return null;
			}
			CVMLong decimals=RT.ensureLong(r.getValue());
			if (decimals==null) return null;
			tokenInfo.decimals=Utils.checkedInt(decimals.longValue());
			return tokenInfo;
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}
	
	public static TokenInfo getFungible(Convex convex, String cnsName) {
		try {
			ACell tokenID=convex.resolve(cnsName).get();
			TokenInfo tokenInfo=new TokenInfo(tokenID);
			tokenInfo.symbol=cnsName.substring(cnsName.lastIndexOf(".")+1);
		
			// We need to get token info
			Result r=convex.querySync("("+getFungibleAddress(convex)+"/decimals "+tokenID+")");
			if (r.isError()) {
				System.err.println("Dubious Token: "+r.toString());
				return null;
			}
			CVMLong decimals=RT.ensureLong(r.getValue());
			if (decimals==null) return null;
			tokenInfo.decimals=Utils.checkedInt(decimals.longValue());
			return tokenInfo;
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}
	
	public static TokenInfo getMulti(Convex convex, String key) {
		try {
			ACell tokenID=Vectors.of(getMultiAddress(convex),Keyword.create(key));
			TokenInfo tokenInfo=new TokenInfo(tokenID);
			tokenInfo.symbol=key;
		
			// We need to get token info
			Result r=convex.querySync("("+getFungibleAddress(convex)+"/decimals "+tokenID+")");
			if (r.isError()) {
				System.err.println("Dubious Token: "+r.toString());
				return null;
			}
			CVMLong decimals=RT.ensureLong(r.getValue());
			if (decimals==null) return null;
			tokenInfo.decimals=Utils.checkedInt(decimals.longValue());
			return tokenInfo;
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	public int getDecimals() {
		return decimals;
	}

	public boolean isConvex() {
		return id==null;
	}

	public BigDecimal decimalAmount(AInteger val) {
		BigDecimal d=new BigDecimal(val.big()).divide(BigDecimal.TEN.pow(decimals));
		return d;
	}


}