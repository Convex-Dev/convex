package convex.benchmarks;

import java.util.ArrayList;
import java.util.Random;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

import convex.core.Block;
import convex.core.BlockResult;
import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.crypto.Ed25519KeyPair;
import convex.core.data.ACell;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.data.SignedData;
import convex.core.exceptions.BadSignatureException;
import convex.core.transactions.ATransaction;
import convex.core.transactions.Transfer;

public class BigBlockBenchmark {

	static final int NUM_ACCOUNTS = 1000;
	static final int NUM_TRANSACTIONS = 1000;
	private static final long INITIAL_FUNDS = 1000000000;
	static ArrayList<AKeyPair> keyPairs = new ArrayList<AKeyPair>();
	static ArrayList<Address> addresses = new ArrayList<Address>();
	public static State state = Benchmarks.STATE;
	public static Block block;
	static ArrayList<SignedData<ATransaction>> transactions = new ArrayList<SignedData<ATransaction>>();

	static {
		for (int i = 0; i < NUM_ACCOUNTS; i++) {
			AKeyPair kp = Ed25519KeyPair.generate();
			keyPairs.add(kp);

			// Create synthetic accounts
			Address a=state.nextAddress();
			state = state.putAccount(a, (AccountStatus.create(INITIAL_FUNDS,kp.getAccountKey())));
			addresses.add(a);
		}
		for (int i = 0; i < NUM_TRANSACTIONS; i++) {
			int src=new Random().nextInt(NUM_ACCOUNTS);
			AKeyPair kp = keyPairs.get(src);
			Address source=addresses.get(src);
			Address target = addresses.get(new Random().nextInt(NUM_ACCOUNTS));
			Transfer t = Transfer.create(source,1, target, 1);
			transactions.add(kp.signData(t));
		}
		block = Block.create(System.currentTimeMillis(),transactions,Benchmarks.FIRST_PEER_KEY);
	}

	@Benchmark
	public void benchmark() throws BadSignatureException {
		BlockResult br=state.applyBlock(block);
		ACell.createPersisted(br.getState());
	}

	public static void main(String[] args) throws Exception {
		Options opt = Benchmarks.createOptions(BigBlockBenchmark.class);
		new Runner(opt).run();
	}
}
