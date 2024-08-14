package convex.benchmarks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.stream.Collectors;

import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import convex.core.State;
import convex.core.crypto.AKeyPair;
import convex.core.data.AccountKey;
import convex.core.data.Address;
import convex.core.init.Init;
import convex.core.lang.Context;

public class Benchmarks {
	
	public static final AKeyPair[] KEYPAIRS = new AKeyPair[] {
		AKeyPair.createSeeded(2),
		AKeyPair.createSeeded(3),
		AKeyPair.createSeeded(5),
		AKeyPair.createSeeded(7),
		AKeyPair.createSeeded(11),
		AKeyPair.createSeeded(13),
		AKeyPair.createSeeded(17),
		AKeyPair.createSeeded(19),
	};
	
	public static ArrayList<AKeyPair> PEER_KEYPAIRS=(ArrayList<AKeyPair>) Arrays.asList(KEYPAIRS).stream().collect(Collectors.toList());
	public static ArrayList<AccountKey> PEER_KEYS=(ArrayList<AccountKey>) Arrays.asList(KEYPAIRS).stream().map(kp->kp.getAccountKey()).collect(Collectors.toList());

	public static final AKeyPair FIRST_PEER_KEYPAIR = KEYPAIRS[0];
	public static final AccountKey FIRST_PEER_KEY = FIRST_PEER_KEYPAIR.getAccountKey();
	
	public static final AKeyPair HERO_KEYPAIR = KEYPAIRS[0];
	public static final AKeyPair VILLAIN_KEYPAIR = KEYPAIRS[1];
	
	public static Address HERO=Init.getGenesisAddress();
	public static Address VILLAIN=HERO.offset(2);
	
	public static final AccountKey HERO_KEY = HERO_KEYPAIR.getAccountKey();

	public static final State STATE = Init.createState(PEER_KEYS);

	public static Options createOptions(Class<?> c) {
		return new OptionsBuilder().include(c.getSimpleName()).warmupIterations(1).measurementIterations(5)
				.warmupTime(TimeValue.seconds(1)).measurementTime(TimeValue.seconds(1)).forks(0).build();
	}

	public static Context context() {
		return Context.create(STATE,HERO);
	}
}
