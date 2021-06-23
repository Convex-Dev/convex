package convex.core.init;

import java.io.IOException;
import java.util.logging.Logger;

import convex.core.Coin;
import convex.core.Constants;
import convex.core.crypto.AKeyPair;
import convex.core.State;
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
 * Static class for generating the initial Convex State
 *
 * "The beginning is the most important part of the work." - Plato, The Republic
 */
public class Init {

    private static final Logger log = Logger.getLogger(Init.class.getName());

	// standard accounts numbers
	public static final Address NULL_ADDRESS = Address.create(0);
	public static final Address INIT_ADDRESS = Address.create(1);

	public static final Address RESERVED_ADDRESS = Address.create(2);
	public static final Address MAINBANK_ADDRESS = Address.create(3);
	public static final Address MAINPOOL_ADDRESS = Address.create(4);
	public static final Address LIVEPOOL_ADDRESS = Address.create(5);
	public static final Address ROOTFUND_ADDRESS = Address.create(6);

	// Built-in special accounts
	public static final Address MEMORY_EXCHANGE_ADDRESS = Address.create(7);
	public static final Address CORE_ADDRESS = Address.create(8);

	public static final Address BASE_FIRST_ADDRESS = Address.create(9);

	public static final int TRUST_LIBRARY_INDEX = 0;
	public static final int REGISTRY_LIBRARY_INDEX = 1;

	public static int BASE_USER_ADDRESS;
	public static int BASE_PEER_ADDRESS;
	public static int BASE_LIBRARY_ADDRESS;

    public static Address TRUST_ADDRESS;
	public static Address REGISTRY_ADDRESS;

	public static State createBaseAccounts(AInitConfig config) {
		// accumulators for initial state maps
		BlobMap<AccountKey, PeerStatus> peers = BlobMaps.empty();
		AVector<AccountStatus> accts = Vectors.empty();

		// governance accounts
		accts = addGovernanceAccount(accts, NULL_ADDRESS, 0L); // Null account
		accts = addGovernanceAccount(accts, INIT_ADDRESS, 0L); // Initialisation Account

		accts = addGovernanceAccount(accts, RESERVED_ADDRESS, 750 * Coin.EMERALD); // 75% for investors

		accts = addGovernanceAccount(accts, MAINBANK_ADDRESS, 240 * Coin.EMERALD); // 24% Foundation

		// Pools for network rewards
		accts = addGovernanceAccount(accts, MAINPOOL_ADDRESS, 1 * Coin.EMERALD); // 0.1% distribute 5% / year ~= 0.0003% //
																			// /day
		accts = addGovernanceAccount(accts, LIVEPOOL_ADDRESS, 5 * Coin.DIAMOND); // 0.0005% = approx 2 days of mainpool feed

		accts = addGovernanceAccount(accts, ROOTFUND_ADDRESS, 8 * Coin.EMERALD); // 0.8% Long term net rewards

		// set up memory exchange. Initially 1GB available at 1000 per byte. (one
		// diamond coin liquidity)
		{
			accts = addMemoryExchange(accts, MEMORY_EXCHANGE_ADDRESS, 1 * Coin.DIAMOND, 1000000000L);
		}

		long USER_ALLOCATION = 994 * Coin.DIAMOND; // remaining allocation to divide between initial user accounts

		// Core library
		accts = addCoreLibrary(accts, CORE_ADDRESS);
		// Core Account should now be fully initialised
		BASE_USER_ADDRESS = accts.size();

		// Set up initial user accounts
		for (int i = 0; i < config.getUserCount(); i++) {
			// TODO: construct addresses
			AKeyPair kp = config.getUserKeyPair(i);
			Address address = config.getUserAddress(i);
			accts = addAccount(accts, address, kp.getAccountKey(), USER_ALLOCATION / 10);
		}

		// Finally add peers
		// Set up initial peers

		BASE_PEER_ADDRESS = accts.size();

		for (int i = 0; i < config.getPeerCount(); i++) {
			AKeyPair kp = config.getPeerKeyPair(i);
			AccountKey peerKey = kp.getAccountKey();
			long peerFunds = USER_ALLOCATION / 10;

			// set a staked fund such that the first peer starts with super-majority
			long stakedFunds = (long) (((i == 0) ? 0.75 : 0.01) * peerFunds);

            // create the peer account first
			Address peerAddress = Address.create(config.getPeerAddress(i));
			accts = addAccount(accts, peerAddress, peerKey, peerFunds - stakedFunds);

            // split peer funds between stake and account
			peers = addPeer(peers, peerKey, peerAddress, stakedFunds);
		}

		BASE_LIBRARY_ADDRESS = accts.size();

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

	public static State createCoreLibraries(AInitConfig config) {
		State s=createBaseAccounts(config);

		// At this point we have a raw initial state with accounts

        // TODO need to fix this as these static vars are changed during this call


        TRUST_ADDRESS = config.getLibraryAddress(TRUST_LIBRARY_INDEX);
		{ // Deploy Trust library
			Context<?> ctx = Context.createFake(s, INIT_ADDRESS);
			ctx = ctx.deployActor(TRUST_CODE);
			if (!TRUST_ADDRESS .equals(ctx.getResult())) throw new Error("Wrong trust address!");
			s = ctx.getState();
		}


		REGISTRY_ADDRESS = config.getLibraryAddress(REGISTRY_LIBRARY_INDEX);
		{ // Deploy Registry Actor to fixed Address
			Context<Address> ctx = Context.createFake(s, INIT_ADDRESS);
			ctx = ctx.deployActor(REGISTRY_CODE);
			if (!REGISTRY_ADDRESS .equals(ctx.getResult())) throw new Error("Wrong registry address!");
			// Note the Registry registers itself upon creation
			s = ctx.getState();
		}

		{ // Register core libraries now that registry exists
			Context<?> ctx = Context.createFake(s, INIT_ADDRESS);
			ctx = ctx.eval(Reader.read("(call *registry* (cns-update 'convex.core " + CORE_ADDRESS + "))"));
			ctx = ctx.eval(Reader.read("(call *registry* (cns-update 'convex.trust " + TRUST_ADDRESS + "))"));
			ctx = ctx.eval(Reader.read("(call *registry* (cns-update 'convex.registry " + REGISTRY_ADDRESS + "))"));
			s = ctx.getState();
			s = register(s, CORE_ADDRESS, "Convex Core Library");
			s = register(s, TRUST_ADDRESS, "Trust Monitor Library");
			s = register(s, MEMORY_EXCHANGE_ADDRESS, "Memory Exchange Pool");
		}

		{ // Test total funds after creating core libraries
			long total = s.computeTotalFunds();
			if (total != Constants.MAX_SUPPLY) throw new Error("Bad total amount: " + total);
		}

		return s;
	}

	public static State createState(AInitConfig config) {
		try {
			State s=createCoreLibraries(config);

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

	public static Address calcAddress(int userCount, int peerCount, int index) {
		return Address.create(BASE_FIRST_ADDRESS.longValue() + userCount + peerCount + index);
	}


	private static State doActorDeploy(State s, String name, String resource) {
		Context<Address> ctx = Context.createFake(s, INIT_ADDRESS);
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
		Context<Address> ctx = Context.createFake(s, MAINBANK_ADDRESS);
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
			Address owner, long initialStake) {
		PeerStatus ps = PeerStatus.create(owner, initialStake, null);
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
