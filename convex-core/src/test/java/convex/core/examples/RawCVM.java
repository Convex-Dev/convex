package convex.core.examples;

import java.util.List;

import convex.core.cvm.Address;
import convex.core.cvm.Context;
import convex.core.cvm.State;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Lists;
import convex.core.init.Init;
import convex.core.lang.Reader;
import convex.test.Samples;

/**
 * Example of executing code on the CVM independently of Convex consensus
 * 
 * This is easy to do and works well since the CVM is effectively a state transition machine that executes CVM operations. 
 */
public class RawCVM {

	public static void main(String[] args) {
		// Create an initial State
		// We use the standard init process because we want the default accounts set up (convex.core, registry etc.)
		// The keys don't really matter if we are using the CVM locally
		List<AccountKey> ks=Lists.of(Samples.ACCOUNT_KEY);
		State state1=Init.createState(ks);	
		
		// some code to execute
		ACell code = Reader.read("[(+ 2 3) *address* (def foo :bar)]");
		
		// Address of the account we want to run code in
		// The genesis Address is a good choice (user address with lots of coins)
		Address address=Init.GENESIS_ADDRESS;
		
		// Create a "Context" which handles CVM state and execution
		// This needs:
		// 1. An initial state
		// 2. An account address to run the code in
		// 3. A juice limit to constrain execution costs
		Context c=Context.create(state1, address, 1000000000L);
		
		// Run code in the current account
		// Note: This performs all CVM state updates
		c=c.run(code);
		
		// Get and print the result
		// (Equivalent to getting the CVM *result* field in Convex Lisp)
		ACell result=c.getResult();
		System.out.println(result);
		
		// Examine something in the resulting state
		State state2=c.getState();
		ACell env=state2.getAccount(address).getEnvironment();
		System.out.println(env);
	}
}
