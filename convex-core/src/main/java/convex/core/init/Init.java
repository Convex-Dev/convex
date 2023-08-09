package convex.core.init;

import java.io.IOException;
import java.util.List;

import convex.core.Coin;
import convex.core.Constants;
import convex.core.State;
import convex.core.data.ACell;
import convex.core.data.AList;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.data.BlobMap;
import convex.core.data.BlobMaps;
import convex.core.data.PeerStatus;
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

	// Standard accounts numbers
	public static final Address NULL_ADDRESS = Address.create(0);
	public static final Address INIT_ADDRESS = Address.create(1);

	// Governance accounts and funding pools
	public static final Address RESERVED_ADDRESS = Address.create(2);
	public static final Address MAINBANK_ADDRESS = Address.create(3);
	public static final Address ROOTFUND_ADDRESS = Address.create(4);
	public static final Address MAINPOOL_ADDRESS = Address.create(5);
	public static final Address LIVEPOOL_ADDRESS = Address.create(6);

	// Built-in special accounts
	public static final Address TEMP_ADDRESS = Address.create(7);
	public static final Address CORE_ADDRESS = Address.create(8);
	public static final Address REGISTRY_ADDRESS = Address.create(9);
    public static final Address TRUST_ADDRESS = Address.create(10);

	// Base for user-specified addresses
	public static final Address GENESIS_ADDRESS = Address.create(11);


	public static State createBaseState(List<AccountKey> genesisKeys) {
		// accumulators for initial state maps
		BlobMap<AccountKey, PeerStatus> peers = BlobMaps.empty();
		AVector<AccountStatus> accts = Vectors.empty();

		long supply = Constants.MAX_SUPPLY;
		
		// Initial accounts
		accts = addGovernanceAccount(accts, NULL_ADDRESS, 0L); // Null account
		accts = addGovernanceAccount(accts, INIT_ADDRESS, 0L); // Initialisation Account

		// Reserved fund
		long reserved = 100*Coin.EMERALD;
		accts = addGovernanceAccount(accts, RESERVED_ADDRESS, reserved); // 75% for investors
		supply-=reserved;
		
		// Foundation governance fund
		long governance = 240*Coin.EMERALD;
		accts = addGovernanceAccount(accts, MAINBANK_ADDRESS, governance); // 24% Foundation
		supply -= governance;

		// Pools for network rewards
		long rootFund = 8 * Coin.EMERALD; // 0.8% Long term net rewards
		accts = addGovernanceAccount(accts, ROOTFUND_ADDRESS, rootFund); 
		supply -= rootFund;
		
		long mainPool = 1 * Coin.EMERALD; // 0.1% distribute 5% / year ~= 0.0003% /day
		accts = addGovernanceAccount(accts, MAINPOOL_ADDRESS, mainPool); 	
		supply -= mainPool;
		
		long livePool = 5 * Coin.DIAMOND; // 0.0005% = approx 2 days of mainpool feed
		accts = addGovernanceAccount(accts, LIVEPOOL_ADDRESS, 5 * Coin.DIAMOND); 
		supply -= livePool;

		accts = addGovernanceAccount(accts, TEMP_ADDRESS, 0 ); 

		// Always have at least one user and one peer setup
		int keyCount = genesisKeys.size();
		assert(keyCount > 0);

		// Core library at static address: CORE_ADDRESS
		accts = addCoreLibrary(accts, CORE_ADDRESS);
		// Core Account should now be fully initialised
		// BASE_USER_ADDRESS = accts.size();

		// Build globals
		AVector<ACell> globals = Constants.INITIAL_GLOBALS;

		// Create the inital state
		State s = State.create(accts, peers, globals, BlobMaps.empty());
		supply-=s.getGlobalMemoryValue().longValue();

		// Add the static defined libraries at addresses: TRUST_ADDRESS, REGISTRY_ADDRESS
		s = createStaticLibraries(s, TRUST_ADDRESS, REGISTRY_ADDRESS);

		// Reload accounts with the libraries
		accts = s.getAccounts();

		// Set up initial user accounts
		assert(accts.count() == GENESIS_ADDRESS.toExactLong());
		{
			long userFunds = (long)(supply*0.8); // 80% to user accounts
			supply -= userFunds;
			
			// Genesis user gets half of all user funds
			long genFunds = userFunds/2;
			accts = addAccount(accts, GENESIS_ADDRESS, genesisKeys.get(0), genFunds);
			userFunds -= genFunds;
			
			for (int i = 0; i < keyCount; i++) {
				Address address = Address.create(accts.count());
				assert(address.toExactLong() == accts.count());
				AccountKey key = genesisKeys.get(i);
				long userBalance = userFunds / (keyCount-i);
				accts = addAccount(accts, address, key, userBalance);
				userFunds -= userBalance;
			}
			assert(userFunds == 0L);
		}

		// Finally add peers
		// Set up initial peers

		// BASE_PEER_ADDRESS = accts.size();
		{
			long peerFunds = supply;
			supply -= peerFunds;
			for (int i = 0; i < keyCount; i++) {
				AccountKey peerKey = genesisKeys.get(i);
				Address peerController = getGenesisPeerAddress(i);
	
				// set a staked fund such that the first peer starts with super-majority
				long peerStake = peerFunds / (keyCount-i);
	
	            // split peer funds between stake and account
				peers = addPeer(peers, peerKey, peerController, peerStake);
				peerFunds -= peerStake;
			}
			assert(peerFunds == 0L);
		}
		

		// Add the new accounts to the state
		s = s.withAccounts(accts);
		// Add peers to the state
		s = s.withPeers(peers);

		{ // Test total funds after creating user / peer accounts
			long total = s.computeTotalFunds();
			if (total != Constants.MAX_SUPPLY) throw new Error("Bad total amount: " + total);
		}

		return s;
	}

	public static State createStaticLibraries(State s, Address trustAddress, Address registryAddress) {

		// At this point we have a raw initial state with no user or peer accounts

		s = doActorDeploy(s, "convex/registry.cvx");
		s = doActorDeploy(s, "convex/trust.cvx");

		{ // Register core libraries now that registry exists
			Context ctx = Context.createFake(s, INIT_ADDRESS);
			ctx = ctx.eval(Reader.read("(call *registry* (cns-update 'convex.core " + CORE_ADDRESS + "))"));
						             
			s = ctx.getState();
			s = register(s, CORE_ADDRESS, "Convex Core Library", "Core utilities accessible by default in any account.");
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

	public static State createState(List<AccountKey> genesisKeys) {
		try {
			State s=createBaseState(genesisKeys);

			// ============================================================
			// Standard library deployment
			s = doActorDeploy(s, "convex/fungible.cvx");
			s = doActorDeploy(s, "convex/trusted-oracle/actor.cvx");
			s = doActorDeploy(s, "convex/oracle.cvx");
			s = doActorDeploy(s, "convex/asset.cvx");
			s = doActorDeploy(s, "torus/exchange.cvx");
			s = doActorDeploy(s, "asset/nft/simple.cvx");
			s = doActorDeploy(s, "asset/nft/basic.cvx");
			s = doActorDeploy(s, "asset/nft/tokens.cvx");
			s = doActorDeploy(s, "asset/box/actor.cvx");
			s = doActorDeploy(s, "asset/box.cvx");
			s = doActorDeploy(s, "asset/multi-token.cvx");
			s = doActorDeploy(s, "asset/share.cvx");
			s = doActorDeploy(s, "convex/play.cvx");
			s = doActorDeploy(s, "convex/did.cvx");
			s = doActorDeploy(s, "lab/curation-market.cvx");
			s = doActorDeploy(s, "convex/trust/ownership-monitor.cvx");
			s = doActorDeploy(s, "convex/trust/delegate.cvx");
			s = doActorDeploy(s, "convex/trust/whitelist.cvx");

			{ // Deploy Currencies
				@SuppressWarnings("unchecked")
				AVector<AVector<ACell>> table = (AVector<AVector<ACell>>) Reader
						.readResourceAsData("torus/currencies.cvx");
				for (AVector<ACell> row : table) {
					s = doCurrencyDeploy(s, row);
				}
			}

			// Final funds check
			long finalTotal = s.computeTotalFunds();
			if (finalTotal != Constants.MAX_SUPPLY)
				throw new Error("Bad total funds in init state amount: " + finalTotal);

			return s;
		} catch (Exception e) {
			throw new RuntimeException("Unable to initialise core",e);
		}

	}

	public static Address calcPeerAddress(int userCount, int index) {
		return Address.create(GENESIS_ADDRESS.toExactLong() + userCount + index);
	}

	public static Address calcUserAddress(int index) {
		return Address.create(GENESIS_ADDRESS.toExactLong() + index);
	}

	// A CVX file contains forms which must be wrapped in a `(do ...)` and deployed as an actor.
	// First form is the name that must be used when registering the actor.
	//
	private static State doActorDeploy(State s, String resource) {
		Context ctx = Context.createFake(s, INIT_ADDRESS);

		try {
			AList<ACell> forms = Reader.readAll(Utils.readResourceAsString(resource));

			ctx = ctx.deployActor(forms.next().toCellArray());
			if (ctx.isExceptional()) throw new Error("Error deploying actor: "+resource+"\n" + ctx.getValue());

			ctx = ctx.eval(Reader.read("(call *registry* (cns-update " + forms.get(0) + " " + ctx.getResult() + "))"));
			if (ctx.isExceptional()) throw new Error("Error while registering actor:" + ctx.getValue());

			return ctx.getState();
		} catch (IOException e) { 
			throw Utils.sneakyThrow(e);
		}
	}

	private static State doCurrencyDeploy(State s, AVector<ACell> row) {
		String symbol = row.get(0).toString();
		double usdValue = RT.jvm(row.get(6));
		long decimals = RT.jvm(row.get(5));

		// Currency liquidity in lowest currency division
		double liquidity = (Long) RT.jvm(row.get(4)) * Math.pow(10, decimals);

		// CVX price for unit
		double price = usdValue * 1000;
		double cvx = price * liquidity / Math.pow(10, decimals);

		long supply = 1000000000000L;
		Context ctx = Context.createFake(s, MAINBANK_ADDRESS);
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

	private static State register(State state, Address origin, String name, String description) {
		Context ctx = Context.createFake(state, origin);
		ctx = ctx.eval(Reader.read("(call *registry* (register {:description \"" + description + "\" :name \"" + name + "\"}))"));
		return ctx.getState();
	}
	
	public static Address getGenesisAddress() {
		return GENESIS_ADDRESS;
	}
	
	public static Address getGenesisPeerAddress(int index) {
		return GENESIS_ADDRESS.offset(index+1);
	}

	private static BlobMap<AccountKey, PeerStatus> addPeer(BlobMap<AccountKey, PeerStatus> peers, AccountKey peerKey,
			Address owner, long initialStake) {
		PeerStatus ps = PeerStatus.create(owner, initialStake, null);
		if (peers.containsKey(peerKey)) throw new IllegalArgumentException("Duplicate peer key");
		return peers.assoc(peerKey, ps);
	}

	private static AVector<AccountStatus> addGovernanceAccount(AVector<AccountStatus> accts, Address a, long balance) {
		if (accts.count() != a.toExactLong()) throw new Error("Incorrect initialisation address: " + a);
		AccountStatus as = AccountStatus.createGovernance(balance);
		accts = accts.conj(as);
		return accts;
	}

	private static AVector<AccountStatus> addCoreLibrary(AVector<AccountStatus> accts, Address a) {
		if (accts.count() != a.toExactLong()) throw new Error("Incorrect core library address: " + a);

		AccountStatus as = AccountStatus.createActor();
		as=as.withEnvironment(Core.ENVIRONMENT);
		as=as.withMetadata(Core.METADATA);
		accts = accts.conj(as);
		return accts;
	}

	private static AVector<AccountStatus> addAccount(AVector<AccountStatus> accts, Address a, AccountKey key,
			long balance) {
		if (accts.count() != a.toExactLong()) throw new Error("Incorrect account address: " + a);
		AccountStatus as = AccountStatus.create(0L, balance, key);
		as = as.withMemory(Constants.INITIAL_ACCOUNT_ALLOWANCE);
		accts = accts.conj(as);
		return accts;
	}


}
