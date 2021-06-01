package convex.core;

import java.io.IOException;
import java.util.logging.Logger;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.data.BlobMap;
import convex.core.data.BlobMaps;
import convex.core.data.Keywords;
import convex.core.data.Maps;
import convex.core.data.PeerStatus;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.Context;
import convex.core.lang.Core;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.util.Utils;

/**
 * Static functionality for generating the initial Convex State
 *
 * "The beginning is the most important part of the work." - Plato, The Republic
 */
public class Init {


	// Governance accounts
	public static final Address NULL = Address.create(0);
	public static final Address INIT = Address.create(1);

	public static final Address RESERVED = Address.create(2);
	public static final Address MAINBANK = Address.create(3);
	public static final Address MAINPOOL = Address.create(4);
	public static final Address LIVEPOOL = Address.create(5);
	public static final Address ROOTFUND = Address.create(6);

	// Built-in special accounts
	public static final Address MEMORY_EXCHANGE = Address.create(7);
	public static final Address CORE_ADDRESS = Address.create(8);

	// Hero and Villain user accounts
	public static final Address HERO = Address.create(9);
	public static final Address VILLAIN = Address.create(10);

	public static final Address FIRST_PEER = Address.create(11);

	public static final Address TRUST_ADDRESS = Address.create(19);
	public static final Address REGISTRY_ADDRESS = Address.create(20);

	private static final Logger log = Logger.getLogger(Init.class.getName());

	public static final int NUM_PEERS = 8;
	public static final int NUM_USERS = 2;

	public static AKeyPair[] KEYPAIRS = new AKeyPair[NUM_PEERS + NUM_USERS];

	public static final AKeyPair HERO_KP;
	public static final AKeyPair VILLAIN_KP;


	static {
		for (int i = 0; i < NUM_USERS; i++) {
			AKeyPair kp = AKeyPair.createSeeded(543212345 + i);
			KEYPAIRS[NUM_PEERS + i] = kp;
		}

		for (int i = 0; i < NUM_PEERS; i++) {
			AKeyPair kp = AKeyPair.createSeeded(123454321 + i);
			KEYPAIRS[i] = kp;
		}

		HERO_KP=KEYPAIRS[NUM_PEERS+0];
		VILLAIN_KP=KEYPAIRS[NUM_PEERS+1];
	}

	public static State createBaseAccounts() {
		// accumulators for initial state maps
		BlobMap<AccountKey, PeerStatus> peers = BlobMaps.empty();
		AVector<AccountStatus> accts = Vectors.empty();

		// governance accounts
		accts = addGovernanceAccount(accts, NULL, 0L); // Null account
		accts = addGovernanceAccount(accts, INIT, 0L); // Initialisation Account

		accts = addGovernanceAccount(accts, RESERVED, 750 * Coin.EMERALD); // 75% for investors

		accts = addGovernanceAccount(accts, MAINBANK, 240 * Coin.EMERALD); // 24% Foundation

		// Pools for network rewards
		accts = addGovernanceAccount(accts, MAINPOOL, 1 * Coin.EMERALD); // 0.1% distribute 5% / year ~= 0.0003% //
																			// /day
		accts = addGovernanceAccount(accts, LIVEPOOL, 5 * Coin.DIAMOND); // 0.0005% = approx 2 days of mainpool feed

		accts = addGovernanceAccount(accts, ROOTFUND, 8 * Coin.EMERALD); // 0.8% Long term net rewards

		// set up memory exchange. Initially 1GB available at 1000 per byte. (one
		// diamond coin liquidity)
		{
			accts = addMemoryExchange(accts, MEMORY_EXCHANGE, 1 * Coin.DIAMOND, 1000000000L);
		}

		long USER_ALLOCATION = 994 * Coin.DIAMOND; // remaining allocation to divide between initial user accounts

		// Core library
		accts = addCoreLibrary(accts, CORE_ADDRESS);
		// Core Account should now be fully initialised

		// Set up initial user accounts
		for (int i = 0; i < NUM_USERS; i++) {
			// TODO: construct addresses
			AKeyPair kp = KEYPAIRS[NUM_PEERS+i];
			Address address = Address.create(HERO.longValue() + i);
			accts = addAccount(accts, address, kp.getAccountKey(), USER_ALLOCATION / 10);
		}

		// Finally add peers
		// Set up initial peers
		for (int i = 0; i < NUM_PEERS; i++) {
			AKeyPair kp = KEYPAIRS[i];
			AccountKey peerKey = kp.getAccountKey();
			long peerFunds = USER_ALLOCATION / 10;

			// set a staked fund such that the first peer starts with super-majority
			long stakedFunds = (long) (((i == 0) ? 0.75 : 0.01) * peerFunds);

			// split peer funds between stake and account
			peers = addPeer(peers, peerKey, stakedFunds);

			Address peerAddress = Address.create(accts.count());
			accts = addAccount(accts, peerAddress, peerKey, peerFunds - stakedFunds);
		}

		// Build globals
		AVector<ACell> globals = Constants.INITIAL_GLOBALS;

		State s = State.create(accts, peers, globals, BlobMaps.empty());

		{ // Test total funds after creating user / peer accounts
			long total = s.computeTotalFunds();
			if (total != Constants.MAX_SUPPLY) throw new Error("Bad total amount: " + total);
		}

		return s;
	}

	static final ACell TRUST_CODE=Reader.readResource("libraries/trust.con");
	static final ACell REGISTRY_CODE=Reader.readResource("actors/registry.con");

	public static State createCoreLibraries() throws IOException {
		State s=createBaseAccounts();

		// At this point we have a raw initial state with accounts

		{ // Deploy Trust library
			Context<?> ctx = Context.createFake(s, INIT);
			ctx = ctx.deployActor(TRUST_CODE);
			if (!TRUST_ADDRESS .equals(ctx.getResult())) throw new Error("Wrong trust address!");
			s = ctx.getState();
		}

		{ // Deploy Registry Actor to fixed Address
			Context<Address> ctx = Context.createFake(s, INIT);
			ctx = ctx.deployActor(REGISTRY_CODE);
			if (!REGISTRY_ADDRESS .equals(ctx.getResult())) throw new Error("Wrong registry address!");
			// Note the Registry registers itself upon creation
			s = ctx.getState();
		}

		{ // Register core libraries now that registry exists
			Context<?> ctx = Context.createFake(s, INIT);
			ctx = ctx.eval(Reader.read("(call *registry* (cns-update 'convex.core " + CORE_ADDRESS + "))"));
			ctx = ctx.eval(Reader.read("(call *registry* (cns-update 'convex.trust " + TRUST_ADDRESS + "))"));
			ctx = ctx.eval(Reader.read("(call *registry* (cns-update 'convex.registry " + REGISTRY_ADDRESS + "))"));
			s = ctx.getState();
			s = register(s, CORE_ADDRESS, "Convex Core Library");
			s = register(s, TRUST_ADDRESS, "Trust Monitor Library");
			s = register(s, MEMORY_EXCHANGE, "Memory Exchange Pool");
		}

		{ // Test total funds after creating core libraries
			long total = s.computeTotalFunds();
			if (total != Constants.MAX_SUPPLY) throw new Error("Bad total amount: " + total);
		}

		return s;
	}

	public static State createState() {
		try {
			State s=createCoreLibraries();

			// ============================================================
			// Standard library deployment

			{ // Deploy Fungible library and register with CNS
				s = doActorDeploy(s, "convex.fungible", "libraries/fungible.con");
			}

			{ // Deploy Oracle Actor
				s = doActorDeploy(s, "convex.trusted-oracle", "actors/oracle-trusted.con");
			}

			{ // Deploy Asset Actor
				s = doActorDeploy(s, "convex.asset", "libraries/asset.con");
			}

			{ // Deploy Torus Actor
				s = doActorDeploy(s, "torus.exchange", "actors/torus.con");
			}

			{ // Deploy NFT Actor
				s = doActorDeploy(s, "asset.nft-tokens", "libraries/nft-tokens.con");
			}

			{ // Deploy Simple NFT Actor
				s = doActorDeploy(s, "asset.simple-nft", "libraries/simple-nft.con");
			}

			{ // Deploy Box Actor
				s = doActorDeploy(s, "asset.box", "libraries/box.con");
			}

			{ // Deploy Currencies
				@SuppressWarnings("unchecked")
				AVector<AVector<ACell>> table = (AVector<AVector<ACell>>) Reader
						.readResourceAsData("torus/currencies.con");
				for (AVector<ACell> row : table) {
					s = doCurrencyDeploy(s, row);
				}
			}

			// Final funds check
			long finalTotal = s.computeTotalFunds();
			if (finalTotal != Constants.MAX_SUPPLY)
				throw new Error("Bad total funds in init state amount: " + finalTotal);

			return s;

		} catch (Throwable e) {
			log.severe("Error in Init initialiser!");
			e.printStackTrace();
			throw new Error(e);
		}
	}

	private static State doActorDeploy(State s, String name, String resource) {
		Context<Address> ctx = Context.createFake(s, INIT);
		ACell form;
		try {
			form = Reader.read(Utils.readResourceAsString(resource));
			ctx = ctx.deployActor(form);
			Address addr = ctx.getResult();
			ctx = ctx.eval(Reader.read("(call *registry* (cns-update '" + name + " " + addr + "))"));

			if (ctx.isExceptional()) throw new Error("Error deploying actor: " + ctx.getValue());
			return ctx.getState();
		} catch (IOException e) {
			throw Utils.sneakyThrow(e);
		}
	}

	private static State doCurrencyDeploy(State s, AVector<ACell> row) {
		String symbol = row.get(0).toString();
		double usdValue = RT.jvm(row.get(6));
		long decimals = RT.jvm(row.get(5));

		// currency liquidity in lowest currency division
		double liquidity = (Long) RT.jvm(row.get(4)) * Math.pow(10, decimals);

		// cvx price for unit
		double price = usdValue * 1000;
		double cvx = price * liquidity / Math.pow(10, decimals);

		long supply = 1000000000000L;
		Context<Address> ctx = Context.createFake(s, MAINBANK);
		ctx = ctx.eval(Reader
				.read("(do (import convex.fungible :as fun) (deploy (fun/build-token {:supply " + supply + "})))"));
		Address addr = ctx.getResult();
		ctx = ctx.eval(Reader.read("(do (import torus.exchange :as torus) (torus/add-liquidity " + addr + " "
				+ liquidity + " " + cvx + "))"));
		if (ctx.isExceptional()) throw new Error("Error adding market liquidity: " + ctx.getValue());
		ctx = ctx.eval(Reader.read("(call *registry* (cns-update 'currency." + symbol + " " + addr + "))"));
		if (ctx.isExceptional()) throw new Error("Error registering currency in CNS: " + ctx.getValue());
		return ctx.getState();
	}

	private static State register(State state, Address origin, String name) {
		Context<?> ctx = Context.createFake(state, origin);
		ctx = ctx.actorCall(REGISTRY_ADDRESS, 0L, Strings.create("register"),
				Maps.of(Keywords.NAME, Strings.create(name)));
		return ctx.getState();
	}

	private static BlobMap<AccountKey, PeerStatus> addPeer(BlobMap<AccountKey, PeerStatus> peers, AccountKey peerKey,
			long initialStake) {
		PeerStatus ps = PeerStatus.create(initialStake, null);
		return peers.assoc(peerKey, ps);
	}

	private static AVector<AccountStatus> addGovernanceAccount(AVector<AccountStatus> accts, Address a, long balance) {
		if (accts.count() != a.longValue()) throw new Error("Incorrect initialisation address: " + a);
		AccountStatus as = AccountStatus.createGovernance(balance);
		accts = accts.conj(as);
		return accts;
	}

	private static AVector<AccountStatus> addMemoryExchange(AVector<AccountStatus> accts, Address a, long balance,
			long allowance) {
		if (accts.count() != a.longValue()) throw new Error("Incorrect memory exchange address: " + a);
		AccountStatus as = AccountStatus.createGovernance(balance).withMemory(allowance);
		accts = accts.conj(as);
		return accts;
	}

	private static AVector<AccountStatus> addCoreLibrary(AVector<AccountStatus> accts, Address a) {
		if (accts.count() != a.longValue()) throw new Error("Incorrect core library address: " + a);

		AccountStatus as = AccountStatus.createActor();
		as=as.withEnvironment(Core.ENVIRONMENT);
		as=as.withMetadata(Core.METADATA);
		accts = accts.conj(as);
		return accts;
	}

	private static AVector<AccountStatus> addAccount(AVector<AccountStatus> accts, Address a, AccountKey key,
			long balance) {
		if (accts.count() != a.longValue()) throw new Error("Incorrect account address: " + a);
		AccountStatus as = AccountStatus.create(0L, balance, key);
		as = as.withMemory(Constants.INITIAL_ACCOUNT_ALLOWANCE);
		accts = accts.conj(as);
		return accts;
	}

}
