package convex.gui.wallet;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import convex.api.Convex;
import convex.core.Result;
import convex.core.data.ACell;
import convex.core.data.Address;
import convex.core.data.Cells;
import convex.core.data.prim.AInteger;
import convex.core.data.prim.CVMLong;

public class TokenInfo {
	private ACell id;

	public TokenInfo(ACell tokenID) {
		this.id=tokenID;
	}

	public ACell getID() {
		return id;
	}
	
	public String symbol() {
		return (id==null)?"CVM":"???";
	}
	
	public int decimals() {
		return (id==null)?9:2;
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
	
	public AInteger getBalance(Convex convex) {
		try {
			if (id==null) {
				long convexBalance=convex.getBalance();
				return CVMLong.create(convexBalance);
			} else {
				Result r=convex.querySync("("+getFungibleAddress(convex)+"/balance "+id+")");
				if (!r.isError()) {
					ACell val=r.getValue();
					if (val instanceof AInteger) {
						return (AInteger) val;
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	static Address fungibleAddress=null;
	private static Address getFungibleAddress(Convex convex) throws TimeoutException, IOException {
		if (fungibleAddress!=null) return fungibleAddress;
		fungibleAddress=convex.querySync("(import convex.fungible)").getValue();
		return fungibleAddress;
	}
}