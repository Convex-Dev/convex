package convex.benchmarks;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Random;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;

import convex.core.Block;
import convex.core.BlockResult;
import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.data.Cells;
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
	public static SignedData<Block> block;
	static ArrayList<SignedData<ATransaction>> transactions = new ArrayList<SignedData<ATransaction>>();

	static {
		for (int i = 0; i < NUM_ACCOUNTS; i++) {
			AKeyPair kp = AKeyPair.generate();
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
		AKeyPair kp=Benchmarks.PEER_KEYPAIRS.get(0);
		block = kp.signData(Block.create(System.currentTimeMillis(),transactions));
	}

	@Benchmark
	public void benchmark() throws BadSignatureException, IOException {
		BlockResult br=state.applyBlock(block);
		Cells.persist(br.getState());
	}

	public static void main(String[] args) throws Exception {
		Options opt = Benchmarks.createOptions(BigBlockBenchmark.class);
		new Runner(opt).run();
	}
}
