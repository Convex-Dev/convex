package convex.java.asset;

import java.util.Map;

import convex.core.cvm.Address;
import convex.java.Convex;

public class Fungible extends BaseAsset<Long> {
	private final Address tokenAddress;
	
	protected Fungible(Convex convex, Address address) {
		super(convex);
		this.tokenAddress=address;
	}
	
	public static Fungible create(Convex convex, Address tokenAddress) {
		return new Fungible(convex,tokenAddress);
	}
	
	@Override
	public Long getBalance() {
		return getBalance(convex.getAddress());
	}

	@Override
	public Long getBalance(Address holder) {
		String code="(do (import convex.fungible :as fungible) (fungible/balance "+tokenAddress.toString()+" "+holder.toString()+"))";
		Map<String,Object> result=convex.query(code);
		
		if (result.containsKey("errorCode")) throw new Error("Token balance query failed" + result);
		
		// should be a success, returning address
		Object value=result.get("value");
		if (value instanceof Long) {
			Long aNum=(Long) value;
			return aNum;
		} else {
			throw new Error("Unexpected return value: "+value);
		}
	}

	public String toString() {
		return "Fungible token with address: "+tokenAddress.toString();
	}
}
