package examples;

import java.util.List;

import convex.core.State;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Lists;
import convex.core.init.Init;
import convex.core.lang.Context;
import convex.core.lang.Reader;
import convex.test.Samples;

/**
 * Example of executing code on the CVM independently of Convex consensus
 */
public class RawCVM {
	static List<AccountKey> ks=Lists.of(Samples.ACCOUNT_KEY);
	static State s=Init.createState(ks);

	public static void main(String[] args) {
		ACell code = Reader.read("[(+ 2 3) *address* (def foo :bar)]");
		
		Context<?> c=Context.createInitial(s, Init.GENESIS_ADDRESS, 1000000000L);
		
		c=c.run(code);
		System.out.println(c.getResult());
		
		State s2=c.getState();
		ACell env=s2.getAccount(Init.GENESIS_ADDRESS).getEnvironment();
		System.out.println(env);
	}
}
