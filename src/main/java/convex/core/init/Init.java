package convex.core.init;

import java.io.IOException;

import convex.core.Coin;
import convex.core.Constants;
import convex.core.State;
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
 * Static class for generating the initial Convex State
 *
 * "The beginning is the most important part of the work." - Plato, The Republic
 */
public class Init {

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
    public static final Address TRUST_ADDRESS = Address.create(9);
	public static final Address REGISTRY_ADDRESS = Address.create(10);

	public static final Address BASE_FIRST_ADDRESS = Address.create(11);


	public static State createBaseState(AInitConfig config) {
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

		// always have at least one user and one peer setup
		assert(config.getUserCount() > 0);
		assert(config.getPeerCount() > 0);
		int totalUserPeerCount = config.getUserCount() + config.getPeerCount();

		// Core library at static address: CORE_ADDRESS
		accts = addCoreLibrary(accts, CORE_ADDRESS);
		// Core Account should now be fully initialised
		// BASE_USER_ADDRESS = accts.size();

		// Build globals
		AVector<ACell> globals = Constants.INITIAL_GLOBALS;

		// create the inital state
		State s = State.create(accts, peers, globals, BlobMaps.empty());

		// add the static defined libraries at addresses: TRUST_ADDRESS, REGISTRY_ADDRESS
		s = createStaticLibraries(s, TRUST_ADDRESS, REGISTRY_ADDRESS);

		// reload accounts with the libraries
		accts = s.getAccounts();

		// Set up initial user accounts
		for (int i = 0; i < config.getUserCount(); i++) {
			// TODO: construct addresses
			AKeyPair kp = config.getUserKeyPair(i);
			Address address = config.getUserAddress(i);
			accts = addAccount(accts, address, kp.getAccountKey(), USER_ALLOCATION / totalUserPeerCount);
		}

		// Finally add peers
		// Set up initial peers

		// BASE_PEER_ADDRESS = accts.size();

		for (int i = 0; i < config.getPeerCount(); i++) {
			AKeyPair kp = config.getPeerKeyPair(i);
			AccountKey peerKey = kp.getAccountKey();
			long peerFunds = USER_ALLOCATION / totalUserPeerCount;

			// set a staked fund such that the first peer starts with super-majority
			long stakedFunds = (long) (((i == 0) ? 0.75 : 0.01) * peerFunds);

            // create the peer account first
			Address peerAddress = Address.create(config.getPeerAddress(i));
			accts = addAccount(accts, peerAddress, peerKey, peerFunds - stakedFunds);

            // split peer funds between stake and account
			peers = addPeer(peers, peerKey, peerAddress, stakedFunds);
		}

		// add the new accounts to the state
		s = s.withAccounts(accts);
		// add peers to the state
		s = s.withPeers(peers);

		{ // Test total funds after creating user / peer accounts
			long total = s.computeTotalFunds();
			if (total != Constants.MAX_SUPPLY) throw new Error("Bad total amount: " + total);
		}

		return s;
	}

	static final ACell TRUST_CODE=Reader.readResource("libraries/trust.con");
	static final ACell REGISTRY_CODE=Reader.readResource("actors/registry.con");

	public static State createStaticLibraries(State s, Address trustAddress, Address registryAddress) {

		// At this point we have a raw initial state with no user or peer accounts


		{ // Deploy Trust library
			Context<?> ctx = Context.createFake(s, INIT_ADDRESS);
			ctx = ctx.deployActor(TRUST_CODE);
			if (!trustAddress .equals(ctx.getResult())) throw new Error("Wrong trust address!");
			s = ctx.getState();
		}


		{ // Deploy Registry Actor to fixed Address
			Context<Address> ctx = Context.createFake(s, INIT_ADDRESS);
			ctx = ctx.deployActor(REGISTRY_CODE);
			if (!registryAddress .equals(ctx.getResult())) throw new Error("Wrong registry address!");
			// Note the Registry registers itself upon creation
			s = ctx.getState();
		}

		{ // Register core libraries now that registry exists
			Context<?> ctx = Context.createFake(s, INIT_ADDRESS);
			ctx = ctx.eval(Reader.read("(call *registry* (cns-update 'convex.core " + CORE_ADDRESS + "))"));
			ctx = ctx.eval(Reader.read("(call *registry* (cns-update 'convex.trust " + trustAddress + "))"));
			ctx = ctx.eval(Reader.read("(call *registry* (cns-update 'convex.registry " + registryAddress + "))"));
			s = ctx.getState();
			s = register(s, CORE_ADDRESS, "Convex Core Library");
			s = register(s, trustAddress, "Trust Monitor Library");
			s = register(s, MEMORY_EXCHANGE_ADDRESS, "Memory Exchange Pool");
		}

		/*
		 * This test below does not correctly calculate the total funds of the state, since
		 * the peers have not yet been added.
		 *
		{ // Test total funds after creating core libraries
			long total = s.computeTotalFunds();
			if (total != Constants.MAX_SUPPLY) throw new Error("Bad total amount: " + total + " should be " + Constants.MAX_SUPPLY);
		}
		*/

		return s;
	}

	public static State createState(AInitConfig config) {
		try {
			State s=createBaseState(config);


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
			e.printStackTrace();
			throw Utils.sneakyThrow(e);
		}
	}

	public static Address calcPeerAddress(int userCount, int index) {
		return Address.create(BASE_FIRST_ADDRESS.longValue() + userCount + index);
	}

	public static Address calcUserAddress(int index) {
		return Address.create(BASE_FIRST_ADDRESS.longValue() + index);
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
