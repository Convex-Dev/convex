package convex.performance;

import java.util.ArrayList;
import java.util.Random;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

import convex.core.Block;
import convex.core.Init;
import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.Ed25519KeyPair;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.data.SignedData;
import convex.core.exceptions.BadSignatureException;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Transfer;

public class TransactionBenchmark {

	static final int NUM_ACCOUNTS = 1000;
	static final int NUM_TRANSACTIONS = 1000;
	private static final long INITIAL_FUNDS = 1000000000;
	static ArrayList<AKeyPair> keyPairs = new ArrayList<AKeyPair>();
	public static State state = Init.STATE;
	public static Block block;
	static ArrayList<SignedData<ATransaction>> transactions = new ArrayList<SignedData<ATransaction>>();

	static {
		for (int i = 0; i < NUM_ACCOUNTS; i++) {
			AKeyPair kp = Ed25519KeyPair.generate();
			keyPairs.add(kp);
			state = state.putAccount(kp.getAddress(), (AccountStatus.create(INITIAL_FUNDS)));
		}
		for (int i = 0; i < NUM_TRANSACTIONS; i++) {
			AKeyPair kp = keyPairs.get(new Random().nextInt(NUM_ACCOUNTS));
			Address target = keyPairs.get(new Random().nextInt(NUM_ACCOUNTS)).getAddress();
			Transfer t = Transfer.create(0, target, 1);
			transactions.add(kp.signData(t));
		}
		block = Block.create(System.currentTimeMillis(),transactions,Init.FIRST_PEER);
	}

	@Benchmark
	public void benchmark() throws BadSignatureException {
		state = state.applyBlock(block).getState();
	}

	public static void main(String[] args) throws Exception {
		Options opt = Benchmarks.createOptions(TransactionBenchmark.class);
		new Runner(opt).run();
	}
}
