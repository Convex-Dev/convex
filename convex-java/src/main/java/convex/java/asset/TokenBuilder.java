package convex.java.asset;

import java.util.Map;

import convex.core.cvm.Address;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.prim.CVMLong;
import convex.java.Convex;

public class TokenBuilder {
	private static final Keyword SUPPLY=Keyword.create("supply");

	private final AMap<Keyword,ACell> config; 
	
	public TokenBuilder(AMap<Keyword,ACell> config) {
		this.config=config;
	}
	
	public TokenBuilder() {
		this(Maps.empty());
	}
	
	private TokenBuilder withConfig(AMap<Keyword, ACell> config) {
		return new TokenBuilder(config);
	}
	
	/**
	 * Creates a TokenBuilder from the current config specifying the given amount of token supply
	 * @param supply Supply for new token
	 * @return Updated TokenBuilder
	 */
	public TokenBuilder withSupply(long supply) {
		if (supply<0) throw new IllegalArgumentException("Requested supply must be non-negative!");
		return withConfig(config.assoc(SUPPLY, CVMLong.create(supply)));
	}
	


	public String generateCode() {
		StringBuilder sb=new StringBuilder();
		sb.append("(do (import convex.fungible :as fungible) \n");
		sb.append("  (deploy [(fungible/build-token "+config.toString()+")\n");
				
		sb.append("]))");
		return sb.toString();
	}
	
	public Fungible deploy(Convex convex) {
		String code=generateCode();
		
		Map<String,Object> result= convex.transact(code);
		if (result.containsKey("errorCode")) throw new Error("Token deployment failed" + result);
		
		// should be a success, returning address
		Object value=result.get("value");
		if (value instanceof Long) {
			Long aNum=(Long) value;
			Address addr=Address.create(aNum);
			return Fungible.create(convex, addr);
		} else {
			throw new Error("Unexpected return value: "+value);
		}
	}
	
	
}
