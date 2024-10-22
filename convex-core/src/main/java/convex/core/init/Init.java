package convex.core.init;

import java.io.IOException;
import java.util.List;

import convex.core.Coin;
import convex.core.Constants;
import convex.core.cpos.CPoSConstants;
import convex.core.cvm.AccountStatus;
import convex.core.cvm.Context;
import convex.core.cvm.PeerStatus;
import convex.core.cvm.State;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AList;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Address;
import convex.core.data.Index;
import convex.core.data.Symbol;
import convex.core.data.Vectors;
import convex.core.lang.Code;
import convex.core.lang.Core;
import convex.core.lang.RT;
import convex.core.lang.Reader;
import convex.core.util.Utils;

/**
 * Static class for generating the initial Convex Genesis State
 *
 * "The beginning is the most important part of the work." - Plato, The Republic
 */
public class Init {

	/**
	 *  Number of special "governance" accounts. These hold all unissued coins
	 */
	public static final int NUM_GOVERNANCE_ACCOUNTS=8;
	
	// Standard accounts numbers
	public static final Address NULL_ADDRESS = Address.create(0);
	public static final Address INIT_ADDRESS = Address.create(1);

	// Governance accounts and funding pools
	public static final Address FOUNDATION_ADDRESS = Address.create(2);
	public static final Address RESERVE_ADDRESS = Address.create(3);
	public static final Address UNRELEASED_ADDRESS = Address.create(4);
	public static final Address DISTRIBUTION_ADDRESS = Address.create(5);
	public static final Address GOVERNANCE_ADDRESS = Address.create(6);
	public static final Address ADMIN_ADDRESS = Address.create(7);

	// Built-in special accounts
	public static final Address CORE_ADDRESS = Address.create(8);
	public static final Address REGISTRY_ADDRESS = Address.create(9);
    public static final Address TRUST_ADDRESS = Address.create(10);

	// Base for genesis addresses
	public static final Address GENESIS_ADDRESS = Address.create(11);
	
	// Controller for genesis peer address
	public static final Address GENESIS_PEER_ADDRESS = Address.create(12);
	
	// First user of Protonet, i.e. @mikera
	public static final Address FIRST_USER_ADDRESS = Address.create(13);

	// Constants
	private static final Index<AccountKey, PeerStatus> EMPTY_PEERS = Index.none();
	private static final Index<ABlob, AVector<ACell>> EMPTY_SCHEDULE = Index.none();
	
	/**
	 * Number of coins issued at genesis (one million)
	 */
	private static final long GENESIS_COINS=1000000*Coin.GOLD;


	/**
	 * Creates the base genesis state (before deployment of standard libraries and actors). This is the minimum state required for Convex operation.
	 * 
	 * @param peerKeys Keys for genesis users and peers
	 * @return Base genesis state
	 */
	public static State createBaseState(AccountKey governanceKey, AccountKey genesisKey, List<AccountKey> peerKeys) {
		
		// accumulators for initial state maps
		Index<AccountKey, PeerStatus> peers = EMPTY_PEERS;
		AVector<AccountStatus> accts = Vectors.empty();

		long supply = Constants.MAX_SUPPLY;
		
		// Null account, cannot ever be used by anyone
		{
			AccountStatus nullAccount=AccountStatus.create(Coin.ZERO,AccountKey.NULL);
			accts=accts.conj(nullAccount);
		}
		
		// "init" Account, used only for network setup and update. Doesn't need coins because we run state updates directly
		{
			AccountStatus initAccount=AccountStatus.create(Coin.ZERO,AccountKey.NULL);
			accts = addAccount(accts, INIT_ADDRESS, initAccount); 
		}

		// Foundation fund for initial operations and fallback governance
		{
			long foundationFund = 1*Coin.EMERALD;
			AccountStatus foundationAccount=AccountStatus.create(foundationFund,governanceKey);
			accts = addAccount(accts, FOUNDATION_ADDRESS, foundationAccount); 
			supply-=foundationFund;
		}
		
		// Foundation reserve fund (rest of foundation 25%, controlled by foundation account)
		{
			long reserve = 248*Coin.EMERALD;
			AccountStatus reserveAccount=AccountStatus.create(reserve,governanceKey);
			reserveAccount=reserveAccount.withController(FOUNDATION_ADDRESS);
			accts = addAccount(accts, RESERVE_ADDRESS, reserveAccount); // 24% Foundation
			supply -= reserve;
		}

		// Reserve for distribution via release curve - 74% for coin purchasers
		{
			long releaseCurveFund = 740 * Coin.EMERALD; 
			AccountStatus releaseCurveAccount=AccountStatus.create(releaseCurveFund,governanceKey);
			releaseCurveAccount=releaseCurveAccount.withController(FOUNDATION_ADDRESS);
			accts = addAccount(accts, UNRELEASED_ADDRESS, releaseCurveAccount); 
			supply -= releaseCurveFund;
		}
		
		// Initial account for release curve distribution - 1% for initial coin purchasers
		{
			long distributionFund = 10 * Coin.EMERALD - 2 *Coin.DIAMOND; 
			AccountStatus releaseCurveAccount=AccountStatus.create(distributionFund,governanceKey);
			releaseCurveAccount=releaseCurveAccount.withController(FOUNDATION_ADDRESS);
			accts = addAccount(accts, DISTRIBUTION_ADDRESS, releaseCurveAccount); 	
			supply -= distributionFund;
		}
		
		// Governance Address, used to manage key network operations e.g. CNS root changes
		{
			long governFund = 1 * Coin.DIAMOND; 
			AccountStatus governanceAccount=AccountStatus.create(governFund,governanceKey);
			governanceAccount=governanceAccount.withController(FOUNDATION_ADDRESS);
			accts = addAccount(accts, GOVERNANCE_ADDRESS, governanceAccount); 	
			supply -= governFund;
		}

		// Admin address, used for non-critical operations, accessible with genesis Key
		{ 
			long admin = 1 * Coin.DIAMOND; 
			AccountStatus governanceAccount=AccountStatus.create(admin,genesisKey);
			governanceAccount=governanceAccount.withController(FOUNDATION_ADDRESS);
			accts = addAccount(accts, ADMIN_ADDRESS, governanceAccount); 	
			supply -= admin;
		}
		
		assert(accts.size()==NUM_GOVERNANCE_ACCOUNTS);
		
		// Core library at static address: CORE_ADDRESS
		accts = addCoreLibrary(accts);
		// Core Account should now be fully initialised
		// BASE_USER_ADDRESS = accts.size();

		
		// Always have at least one user and one peer setup
		int keyCount = peerKeys.size();
		assert(keyCount > 0);


		// Build globals
		AVector<ACell> globals = Constants.INITIAL_GLOBALS;

		// Create the initial state with static libraries and memory allowances. 
		// We now have a functional CVM State!
		State s = State.create(accts, peers, globals, EMPTY_SCHEDULE);
		{
			supply-=s.getGlobalMemoryValue().longValue();
			
			// There should be at least 100,000 Convex Gold for genesis to succeed, to be distributed to genesis account(s)
			assert(supply>100000*Coin.GOLD);
	
			// Add the static defined libraries at addresses: TRUST_ADDRESS, REGISTRY_ADDRESS
			s = addStaticLibraries(s);
			
			// Add the basic CNS tree
			s=addCNSBaseTree(s);
	
			// Reload accounts with the base libraries
			accts = s.getAccounts();
		}

		// Set up initial user accounts, genesis user plus one for each genesis peer. 
		assert(accts.count() == GENESIS_ADDRESS.longValue());
		{
			long userFunds = (long)(supply*0.8); // 80% to user accounts
			supply -= userFunds;
			
			// Genesis user gets half of all user funds
			long genFunds = userFunds/2;
			accts = addAccount(accts, GENESIS_ADDRESS, peerKeys.get(0), genFunds);
			userFunds -= genFunds;
			
			// One Peer account for each  specified key (including initial genesis user)
			for (int i = 0; i < keyCount; i++) {
				Address address = Address.create(accts.count());
				assert(address.longValue() == accts.count());
				AccountKey key = peerKeys.get(i);
				long userBalance = userFunds / (keyCount-i);
				accts = addAccount(accts, address, key, userBalance);
				userFunds -= userBalance;
			}
			assert(userFunds == 0L);
		}

		// Finally add initial peers

		// BASE_PEER_ADDRESS = accts.size();
		{
			long peerFunds = supply;
			supply -= peerFunds;
			for (int i = 0; i < keyCount; i++) {
				AccountKey peerKey = peerKeys.get(i);
				Address peerController = getGenesisPeerAddress(i);
	
				// Divide funds among peers
				long peerStake = peerFunds / (keyCount-i);
	
	            // Add peer with specified stake
				peers = addPeer(peers, peerKey, peerController, peerStake);
				peerFunds -= peerStake;
			}
			assert(peerFunds == 0L);
		}
		
		// Add the new accounts to the State
		s = s.withAccounts(accts);
		// Add peers to the State
		s = s.withPeers(peers);

		{ // Test total funds after creating user / peer accounts
			long total = s.computeTotalBalance();
			if (total != Constants.MAX_SUPPLY) throw new Error("Bad total amount: " + total);
			
			long issuedSupply=s.computeSupply();
			if (issuedSupply!= GENESIS_COINS) throw new Error("Bad genesis supply: " + issuedSupply);
		}

		return s;
	}
	
	public static AVector<AccountStatus> addAccount(AVector<AccountStatus> accts, Address a, AccountKey key,
			long balance) {
		if (accts.count() != a.longValue()) throw new Error("Incorrect account address: " + a);
		AccountStatus as = AccountStatus.create(0L, balance, key);
		as = as.withMemory(CPoSConstants.INITIAL_ACCOUNT_ALLOWANCE);
		accts = accts.conj(as);
		return accts;
	}


	/**
	 * Creates the built-in static Libraries (registry, trust)
	 * @param s State to add core libraries to
	 * @param trustAddress
	 * @param registryAddress
	 * @return Updates state
	 */
	private static State addStaticLibraries(State s) {

		// At this point we have a raw initial State with no user or peer accounts
		s = doActorDeploy(s, "/convex/core/registry.cvx",false);
		s = doActorDeploy(s, "/convex/core/trust.cvx",false);

		{ // Register core library now that registry exists
			Context ctx = Context.create(s, INIT_ADDRESS);						             
			s = ctx.getState();
			s = register(s, CORE_ADDRESS, "Convex Core Library", "Core utilities accessible by default in any account.");
			
			s = register(s, FOUNDATION_ADDRESS, "Foundation Governance", "Master network governance account.");
			s = register(s, RESERVE_ADDRESS, "Foundation Reserve", "Unreleased Foundation coin reserve.");
			s = register(s, UNRELEASED_ADDRESS, "Release Curve Reserve", "Release curve unreleased coins.");
			s = register(s, DISTRIBUTION_ADDRESS, "Release Curve Pre-Distribution", "Release curve coins for future distribution.");
			s = register(s, GOVERNANCE_ADDRESS, "Network Governance", "Network governance account.");
			s = register(s, ADMIN_ADDRESS, "Network Admin", "Network admin accouns.");
		}

		return s;
	}

	public static State createState(List<AccountKey> genesisKeys) {
		return createState(genesisKeys.get(0),genesisKeys);
	}
	
	public static State createState(AccountKey genesisKey,List<AccountKey> peerKeys) {
		return createState(genesisKey,genesisKey,peerKeys);
	}
	
	public static State createState(AccountKey governanceKey, AccountKey genesisKey,List<AccountKey> peerKeys) {
		State s=createBaseState(governanceKey, genesisKey, peerKeys);
		
		s = addStandardLibraries(s);
		s = addTestingCurrencies(s);
		s = addCNSExtraTree(s);

		// Final funds check
		long finalTotal = s.computeTotalBalance();
		if (finalTotal != Constants.MAX_SUPPLY)
			throw new Error("Bad total funds in init state amount: " + finalTotal);

		return s;
	}

	private static State addTestingCurrencies(State s)  {
		try {
			@SuppressWarnings("unchecked")
			AVector<AVector<ACell>> table = (AVector<AVector<ACell>>) Reader
					.readResourceAsData("/convex/torus/genesis-currencies.cvx");
			for (AVector<ACell> row : table) {
				s = doCurrencyDeploy(s, row);
			}
		} catch (IOException e) {
			throw new Error("Failure reading source data for currencies",e);
		}
		return s;
	}

	private static State addStandardLibraries(State s) {
		s = doActorDeploy(s, "/convex/asset/fungible.cvx");
		s = doActorDeploy(s, "/convex/lab/trusted-oracle/actor.cvx");
		s = doActorDeploy(s, "/convex/lab/oracle.cvx");
		s = doActorDeploy(s, "/convex/asset/asset.cvx");
		s = doActorDeploy(s, "/convex/torus/exchange.cvx");
		s = doActorDeploy(s, "/convex/asset/nft/simple.cvx");
		s = doActorDeploy(s, "/convex/asset/nft/basic.cvx");
		s = doActorDeploy(s, "/convex/asset/nft/tokens.cvx");
		s = doActorDeploy(s, "/convex/asset/box/actor.cvx");
		s = doActorDeploy(s, "/convex/asset/box.cvx");
		s = doActorDeploy(s, "/convex/asset/multi-token.cvx");
		s = doActorDeploy(s, "/convex/asset/share.cvx");
		s = doActorDeploy(s, "/convex/asset/market/trade.cvx");
		s = doActorDeploy(s, "/convex/asset/wrap/convex.cvx");
		s = doActorDeploy(s, "/convex/lab/play.cvx");
		s = doActorDeploy(s, "/convex/lab/did.cvx");
		s = doActorDeploy(s, "/convex/lab/curation-market.cvx");
		s = doActorDeploy(s, "/convex/trust/ownership-monitor.cvx");
		s = doActorDeploy(s, "/convex/trust/delegate.cvx");
		s = doActorDeploy(s, "/convex/trust/whitelist.cvx");
		s = doActorDeploy(s, "/convex/trust/monitors.cvx");
		s = doActorDeploy(s, "/convex/trust/governance.cvx");
		s = doActorDeploy(s, "/convex/asset/spatial.cvx");
		// s = doActorDeploy(s, "convex/user.cvx");
		return s;
	}
	
	private static State addCNSBaseTree(State s) {
		Context ctx=Context.create(s, GOVERNANCE_ADDRESS);
		ctx=ctx.eval(Reader.read("(*registry*/create 'convex)"));
		ctx.getResult();
		
		// convex.cns is alias to root cns namespace?
		ctx=ctx.eval(Reader.read("(*registry*/create 'convex.cns "+REGISTRY_ADDRESS+" *address* nil [#9 []])"));
		ctx.getResult();

		ctx=ctx.eval(Reader.read("(*registry*/create 'convex.registry "+REGISTRY_ADDRESS+")"));
		ctx.getResult();

		ctx=ctx.eval(Reader.read("(*registry*/create 'convex.core "+CORE_ADDRESS+")"));
		ctx.getResult();
		
		ctx=ctx.eval(Reader.read("(*registry*/create 'convex.trust "+TRUST_ADDRESS+")"));
		ctx.getResult();
		
		// check we can get access to general trust monitors
		//ctx=ctx.eval(Reader.read("(import convex.trust.monitors :as mon)"));
		//ctx.getResult();
		
		//ctx=ctx.eval(Reader.read("(def tmon (mon/permit-actions :create))"));
		//ctx.getResult();

		//ctx=ctx.eval(Reader.read("(do ("+TRUST_ADDRESS+"/change-control [*registry* [\"user\"]] tmon))"));
		//ctx.getResult();
		
		s=ctx.getState();
		return s;
	}
	
	private static State addCNSExtraTree(State s) {
		Context ctx=Context.create(s, GOVERNANCE_ADDRESS);
		
		// ctx=ctx.eval(Reader.read("(*registry*/create 'zoo "+TRUST_ADDRESS+")"));
		// ctx.getResult();

		
		s=ctx.getState();
		return s;
	}

	public static Address calcPeerAddress(int userCount, int index) {
		return Address.create(GENESIS_ADDRESS.longValue() + userCount + index);
	}

	public static Address calcUserAddress(int index) {
		return Address.create(GENESIS_ADDRESS.longValue() + index);
	}
	
	private static State doActorDeploy(State s, String resource) {
		return doActorDeploy(s,resource,true);
	}

	// A CVX file contains forms which must be wrapped in a `(do ...)` and deployed as an actor.
	// First form is the name that must be used when registering the actor.
	//
	private static State doActorDeploy(State s, String resource, boolean addCNS) {
		Context ctx = Context.create(s, GOVERNANCE_ADDRESS);
		ACell ADD_NETWORK_GOVERNANCE=Reader.read("(set-controller "+GOVERNANCE_ADDRESS+")");
		
		try {
			AList<ACell> forms = Reader.readAll(Utils.readResourceAsString(resource));
			AList<ACell> code=forms.drop(1);
			
			// Add network governance controller
			code=code.cons(ADD_NETWORK_GOVERNANCE);
			
			ctx = ctx.deploy(code.toCellArray());
			if (ctx.isExceptional()) throw new Error("Error deploying actor: "+resource+"\n" + ctx.getValue());
			Address addr=ctx.getResult();
			
			if (addCNS) {
				@SuppressWarnings("unchecked")
				AList<Symbol> qsym=(AList<Symbol>) forms.get(0);
				Symbol sym=qsym.get(1);
				ctx = ctx.eval(Code.cnsUpdate(sym, addr,GOVERNANCE_ADDRESS));
				if (ctx.isExceptional()) throw new Error("Error while registering actor:" + ctx.getValue());
			}

			return ctx.getState();
		} catch (IOException e) { 
			throw new Error(e);
		}
	}

	private static State doCurrencyDeploy(State s, AVector<ACell> row) {
		String symName = row.get(0).toString();
		String name = row.get(1).toString();
		String desc = row.get(2).toString();
		double usdPrice = RT.jvm(row.get(6)); // Value in USD for currency, e.g. USD=1.0, GBP=1.3
		long decimals = RT.jvm(row.get(5)); // Decimals for lowest currency unit, e.g. USD = 2
		long usdValue=(Long) RT.jvm(row.get(4)); // USD value of liquidity in currency
		
		// number of sub-units in currency
		long subDivisions=Math.round(Math.pow(10, decimals));
		
		// Currency liquidity (in lowest currency subdivision)
		double liquidity =  (usdValue/usdPrice)*subDivisions;
		long supply = Math.round(liquidity);
		
		// CVX price for currency
		double cvxPrice = usdPrice * 1000000000; // One CVX Gold = 1 USD in genesis
		double cvx = cvxPrice * supply / subDivisions;
		
		String metaString="{:name "+RT.print(name)+ ":desc "+RT.print(desc)+"}";

		Context ctx = Context.create(s, GENESIS_ADDRESS);
		ctx = ctx.eval(Reader
				.read("(do "
						+ "(import convex.fungible :as fun) "
						+ "(deploy "
						  + "'(call *registry* (register "+metaString+"))"
						  + "(fun/build-token {:supply " + supply + " :decimals "+decimals+"})"
						  +")"
					+ ")"));
		Address addr = ctx.getResult();
		ctx = ctx.eval(Reader.read("(do (import torus.exchange :as torus) (torus/add-liquidity " + addr + " "
				+ (supply / 2) + " " + (cvx / 2) + "))"));
		if (ctx.isExceptional()) throw new Error("Error adding market liquidity: " + ctx.getValue());
		
		Symbol sym=Symbol.create("currency."+symName);
		ctx = ctx.forkWithAddress(GOVERNANCE_ADDRESS);
		ctx = ctx.eval(Code.cnsUpdate(sym, addr,GOVERNANCE_ADDRESS));
		if (ctx.isExceptional()) throw new Error("Error registering currency in CNS: " + ctx.getValue());
		return ctx.getState();
	}

	private static State register(State state, Address origin, String name, String description) {
		Context ctx = Context.create(state, origin);
		ctx = ctx.eval(Reader.read("(call *registry* (register {:description \"" + description + "\" :name \"" + name + "\"}))"));
		return ctx.getState();
	}
	
	public static Address getGenesisAddress() {
		return GENESIS_ADDRESS;
	}
	
	public static Address getGenesisPeerAddress(int index) {
		return GENESIS_ADDRESS.offset(index+1);
	}

	private static Index<AccountKey, PeerStatus> addPeer(Index<AccountKey, PeerStatus> peers, AccountKey peerKey,
			Address owner, long initialStake) {
		PeerStatus ps = PeerStatus.create(owner, initialStake, null);
		if (peers.containsKey(peerKey)) throw new IllegalArgumentException("Duplicate peer key");
		return peers.assoc(peerKey, ps);
	}
	
	private static AVector<AccountStatus> addAccount(AVector<AccountStatus> accts, Address address, AccountStatus account) {
		long num=address.longValue();
		if (accts.count() != num) throw new Error("Incorrect initialisation address: " + address);
		accts = accts.conj(account);
		return accts;
	}

	private static AVector<AccountStatus> addCoreLibrary(AVector<AccountStatus> accts) {
		if (accts.count() != CORE_ADDRESS.longValue()) throw new Error("Incorrect core library address: " + accts.count());

		AccountStatus as = AccountStatus.createActor();
		as=as.withEnvironment(Core.ENVIRONMENT);
		as=as.withMetadata(Core.METADATA);
		accts = accts.conj(as);
		return accts;
	}


}
