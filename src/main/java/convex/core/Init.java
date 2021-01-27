package convex.core;

import java.util.logging.Logger;

import convex.core.crypto.AKeyPair;
import convex.core.data.AHashMap;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.data.BlobMap;
import convex.core.data.BlobMaps;
import convex.core.data.Keywords;
import convex.core.data.Maps;
import convex.core.data.PeerStatus;
import convex.core.data.Sets;
import convex.core.data.Strings;
import convex.core.data.Symbol;
import convex.core.data.Vectors;
import convex.core.lang.Context;
import convex.core.lang.Core;
import convex.core.lang.Reader;
import convex.core.lang.Symbols;

/**
 * Static functionality for generating the initial Convex State
 *
 * "The beginning is the most important part of the work." 
 * - Plato, The Republic
 */
public class Init {
	/**
	 * The initial state of the Convex network
	 */
	public static final State STATE;
	
	public static final int NUM_GOVERNANCE = 9;
	public static final int NUM_PEERS = 8;
	public static final int NUM_USERS = 2;
	public static final int NUM_LIBRARIES = 4;

	public static final long USER_ALLOCATION;

	
	// Governance accounts
	public static final Address NULL = Address.create(0);
	public static final Address INIT = Address.create(1);
	
	
	public static final Address RESERVED = Address.create(2);
	public static final Address MAINBANK = Address.create(3);
	public static final Address MAINPOOL = Address.create(4);
	public static final Address LIVEPOOL = Address.create(5);
	public static final Address ROOTFUND = Address.create(6);
	
	// Built-in special accounts
	public static final Address MEMORY_EXCHANGE=Address.create(7);
	public static final Address CORE_ADDRESS=Address.create(8);
	
	// Hero is 9, VILLAIN is 10
	public static final Address HERO=Address.create(9);
	public static final Address VILLAIN=Address.create(10);

	public static final Address FIRST_PEER=Address.create(11);
	
	public static final AccountKey FIRST_PEER_KEY;


	// Addresses for initial Actors
	public static final Address REGISTRY_ADDRESS; // 19
	public static final Address TRUST_ADDRESS; // 20
	public static final Address ORACLE_ADDRESS; // 21
	public static final Address FUNGIBLE_ADDRESS; // 22 
	public static final Address ASSET_ADDRESS; // 23

	public static AKeyPair[] KEYPAIRS = new AKeyPair[NUM_PEERS + NUM_USERS];

	public static final AKeyPair HERO_KP;
	public static final AKeyPair VILLAIN_KP;






	private static final Logger log = Logger.getLogger(Init.class.getName());

	public static final AccountStatus CORE_ACCOUNT;


	static {
		try {
			// accumulators for initial state maps
			BlobMap<AccountKey, PeerStatus> peers = BlobMaps.empty();
			AVector<AccountStatus> accts = Vectors.empty();


			// governance accounts
			accts = addGovernanceAccount(accts, NULL, 0L); // Null account
			accts = addGovernanceAccount(accts, INIT, 0L); // Initialisation Account
			
			accts = addGovernanceAccount(accts, RESERVED, 750*Coin.EMERALD); // 75% for investors
						
			accts = addGovernanceAccount(accts, MAINBANK, 240*Coin.EMERALD); // 24% Foundation
			
			// Pools for network rewards
			accts = addGovernanceAccount(accts, MAINPOOL, 1*Coin.EMERALD); // 0.1% distribute 5% / year ~= 0.0003%																// /day
			accts = addGovernanceAccount(accts, LIVEPOOL, 5*Coin.DIAMOND); // 0.0005% = approx 2 days of mainpool feed
			
			accts = addGovernanceAccount(accts, ROOTFUND, 8*Coin.EMERALD); // 0.8% Long term net rewards

			// set up memory exchange. Initially 1GB available at 1000 per byte. (one diamond coin liquidity)
			{
				accts = addMemoryExchange(accts, MEMORY_EXCHANGE, 1*Coin.DIAMOND,1000000000L);
			}
			
			USER_ALLOCATION = 994*Coin.DIAMOND; // remaining allocation to divide between initial user accounts
			
			// Core library
			accts=addCoreLibrary(accts,CORE_ADDRESS);
			// Core Account should now be fully initialised
			CORE_ACCOUNT=accts.get(CORE_ADDRESS.longValue());

			// Set up initial user accounts
			for (int i = 0; i < NUM_USERS; i++) {
				AKeyPair kp = AKeyPair.createSeeded(543212345 + i);
				KEYPAIRS[NUM_PEERS + i] = kp;
				// TODO: construct addresses
				Address address = Address.create(HERO.longValue()+i);
				accts = addAccount(accts, address,kp.getAccountKey(), USER_ALLOCATION / 10);
			}

			HERO_KP = KEYPAIRS[NUM_PEERS + 0];
			VILLAIN_KP = KEYPAIRS[NUM_PEERS + 1];
			
			// Finally add peers
			// Set up initial peers
			for (int i = 0; i < NUM_PEERS; i++) {
				AKeyPair kp = AKeyPair.createSeeded(123454321 + i);
				KEYPAIRS[i] = kp;
				AccountKey peerKey = kp.getAccountKey();
				long peerFunds = USER_ALLOCATION / 10;

				// set a staked fund such that the first peer starts with super-majority
				long stakedFunds = (long) (((i == 0) ? 0.75 : 0.01) * peerFunds);

				// split peer funds between stake and account
				peers = addPeer(peers, peerKey, stakedFunds);
				
				Address peerAddress=Address.create(accts.count());
				accts = addAccount(accts, peerAddress, peerKey, peerFunds - stakedFunds);
			}
			
			FIRST_PEER_KEY=KEYPAIRS[0].getAccountKey();
			if (accts.count()!=FIRST_PEER.longValue()+NUM_PEERS) {
				throw new Error("Unexpected number of accounts after adding peers: "+accts.count());
			}

			// Build globals
			AHashMap<Symbol, Object> globals = Maps.of(Symbols.TIMESTAMP, Constants.INITIAL_TIMESTAMP, Symbols.FEES, 0L,
					Symbols.JUICE_PRICE, Constants.INITIAL_JUICE_PRICE);

			State s = State.create(accts, peers, Sets.empty(), globals, BlobMaps.empty());

			long total = s.computeTotalFunds();
			if (total != Constants.MAX_SUPPLY) throw new Error("Bad total amount: " + total);
			if (s.getPeers().size() != NUM_PEERS) throw new Error("Bad peer count: " + s.getPeers().size());
			if (s.getAccounts().size() != NUM_PEERS + NUM_USERS + NUM_GOVERNANCE) throw new Error("Bad account count");

			// At this point we have a raw initial state with accounts
			
			{ // Deploy Registry Actor to fixed Address
				Context<?> ctx = Context.createFake(s, HERO);
				Object form=Reader.readResource("actors/registry.con");
				ctx = ctx.deployActor(form);
				REGISTRY_ADDRESS=(Address) ctx.getResult();
				// Note the Registry registers itself upon creation
				s = ctx.getState();
			}
			
			{ // Register core libraries now that registry exists
				Context<?> ctx = Context.createFake(s, HERO);
				ctx=ctx.eval(Reader.read("(call *registry* (cns-update 'convex.core "+CORE_ADDRESS+"))"));
				s=ctx.getState();
				s = register(s,CORE_ADDRESS,"Convex Core Library");
				s = register(s,MEMORY_EXCHANGE,"Memory Exchange Pool");
			}
			
			{ // Deploy Trust library and register with CNS
				Context<?> ctx = Context.createFake(s, HERO);
				Object form=Reader.readResource("libraries/trust.con");
				ctx = ctx.deployActor(form);
				Address addr=(Address) ctx.getResult();
				ctx=ctx.eval(Reader.read("(call *registry* (cns-update 'convex.trust "+addr+"))"));
				TRUST_ADDRESS=addr;
				// Note the Registry registers itself upon creation
				s = ctx.getState();
			}
			
			{ // Deploy Fungible library and register with CNS
				Context<?> ctx = Context.createFake(s, HERO);
				Object form=Reader.readResource("libraries/fungible.con");
				ctx = ctx.deployActor(form);
				Address addr=(Address) ctx.getResult();
				ctx=ctx.eval(Reader.read("(call *registry* (cns-update 'convex.fungible "+addr+"))"));
				FUNGIBLE_ADDRESS=addr;
				// Note the Registry registers itself upon creation
				s = ctx.getState();
			}
			
			{ // Deploy Oracle Actor
				Context<?> ctx = Context.createFake(s, HERO);
				Object form=Reader.readResource("actors/oracle-trusted.con");
				ctx = ctx.deployActor(form);
				ORACLE_ADDRESS = (Address) ctx.getResult();
				s = register(ctx.getState(),ORACLE_ADDRESS,"Oracle Actor (default)");
			}
			
			{ // Deploy Asset Actor
				Context<?> ctx = Context.createFake(s, HERO);
				Object form=Reader.readResource("libraries/asset.con");
				ctx = ctx.deployActor(form);
				if (ctx.isExceptional()) {
					log.severe("Failure to deploy convex.asset: "+ctx.getExceptional());
				}
				ASSET_ADDRESS = (Address) ctx.getResult();
				s=ctx.getState();
			}
			

			
			

			STATE = s;
		} catch (Throwable e) {
			log.severe("Error in Init initialiser!");
			e.printStackTrace();
			throw new Error(e);
		}
	}
	
	private static State register(State state,Address origin, String name) {
		Context<?> ctx = Context.createFake(state, origin);
		ctx = ctx.actorCall(REGISTRY_ADDRESS, 0, "register",
				Maps.of(Keywords.NAME, Strings.create(name)));
		return ctx.getState();
	}

	private static BlobMap<AccountKey, PeerStatus> addPeer(BlobMap<AccountKey, PeerStatus> peers, AccountKey peerKey,
			long initialStake) {
		PeerStatus ps = PeerStatus.create(initialStake, null);
		return peers.assoc(peerKey, ps);
	}

	private static AVector<AccountStatus> addGovernanceAccount(AVector<AccountStatus> accts,
			Address a, long balance) {
		if (accts.count()!=a.longValue()) throw new Error("Incorrect initialisation address: "+a);
		AccountStatus as = AccountStatus.createGovernance(balance);
		accts = accts.conj(as);
		return accts;
	}
	
	private static AVector<AccountStatus> addMemoryExchange(AVector<AccountStatus> accts,
			Address a, long balance, long allowance) {
		if (accts.count()!=a.longValue()) throw new Error("Incorrect memory exchange address: "+a);
		AccountStatus as = AccountStatus.createGovernance(balance).withAllowance(allowance);
		accts = accts.conj(as);
		return accts;
	}
	
	private static AVector<AccountStatus> addCoreLibrary(AVector<AccountStatus> accts, Address a) {
		if (accts.count()!=a.longValue()) throw new Error("Incorrect core library address: "+a);

		AccountStatus as = AccountStatus.createActor(0L, Core.CORE_NAMESPACE);
		accts = accts.conj(as);
		return accts;
	}

	private static AVector<AccountStatus> addAccount(AVector<AccountStatus> accts, Address a,
			AccountKey key,long balance) {
		if (accts.count()!=a.longValue()) throw new Error("Incorrect account address: "+a);
		AccountStatus as = AccountStatus.create(0L, balance,key); 
		as=as.withAllowance(Constants.INITIAL_ACCOUNT_ALLOWANCE);
		accts = accts.conj(as);
		return accts;
	}

}
