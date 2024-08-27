package convex.core.examples;

import java.io.IOException;

import convex.etch.EtchStore;

/**
 * Test class for processing of Belief Delta messages
 */
public class BeliefDeltaSimulation {

	public static EtchStore store1=createTemp("store-one");
	public static EtchStore store2=createTemp("store-two");
	
	public static void main(String[] args) {

		
	}

	private static EtchStore createTemp(String name) {
		try {
		return EtchStore.createTemp(name);
		} catch (IOException e) {
			throw new Error(e);
		}
	}

}
