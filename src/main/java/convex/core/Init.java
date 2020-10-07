package convex.core;

import java.util.logging.Logger;

import convex.core.crypto.AKeyPair;
import convex.core.data.AHashMap;
import convex.core.data.AccountStatus;
import convex.core.data.Address;
import convex.core.data.Amount;
import convex.core.data.BlobMap;
import convex.core.data.BlobMaps;
import convex.core.data.Keywords;
import convex.core.data.Maps;
import convex.core.data.PeerStatus;
import convex.core.data.Sets;
import convex.core.data.Strings;
import convex.core.data.Symbol;
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
	
	// Governance accounts
	public static final Address NULL_ADDRESS = Address.dummy("0");
	public static final Address RESERVED = Address.dummy("1");
	public static final Address MAINBANK = Address.dummy("2");
	public static final Address MAINPOOL = Address.dummy("3");
	public static final Address LIVEPOOL = Address.dummy("4");
	public static final Address ROOTFUND = Address.dummy("5");
	
	// Built-in special accounts
	public static final Address MEMORY_EXCHANGE=Address.dummy("a");
	public static final Address TRUST_ADDRESS=Address.dummy("b");
	public static final Address CORE_ADDRESS=Address.dummy("c");
	public static final Address REGISTRY_ADDRESS=Address.dummy("f");
	
	public static final Address ORACLE_ADDRESS;

	public static final int NUM_GOVERNANCE = 6;
	public static final int NUM_PEERS = 8;
	public static final int NUM_USERS = 2;
	public static final int NUM_LIBRARIES = 1;

	public static final long USER_ALLOCATION;

	public static AKeyPair[] KEYPAIRS = new AKeyPair[NUM_PEERS + NUM_USERS];

	public static final Address HERO;
	public static final Address VILLAIN;
	public static final Address FIRST_PEER;

	public static final AKeyPair HERO_KP;
	public static final AKeyPair VILLAIN_KP;


	public static final AHashMap<Symbol, Object> INITIAL_GLOBALS = Maps.of(Symbols.TIMESTAMP,
			Constants.INITIAL_TIMESTAMP, Symbols.FEES, 0L, Symbols.JUICE_PRICE, Constants.INITIAL_JUICE_PRICE);




	private static final Logger log = Logger.getLogger(Init.class.getName());

	public static final AccountStatus CORE_ACCOUNT;

	static {
		try {
			// accumulators for initial state maps
			BlobMap<Address, PeerStatus> peers = BlobMaps.empty();
			BlobMap<Address, AccountStatus> accts = BlobMaps.empty();

			// Core library
			accts=addCoreLibrary(accts,CORE_ADDRESS);
			// Core Account should now be fully initialised
			CORE_ACCOUNT=accts.get(CORE_ADDRESS);

			// governance accounts
			accts = addGovernanceAccount(accts, RESERVED, 900000000000000000L); // 90%
			accts = addGovernanceAccount(accts, MAINBANK, 90000000000000000L); // 9%
			accts = addGovernanceAccount(accts, ROOTFUND, 9000000000000000L); // 1%
			accts = addGovernanceAccount(accts, MAINPOOL, 998000000000000L); // 0.1% distribute 5% / year ~= 0.0003%
																				// /day
			accts = addGovernanceAccount(accts, LIVEPOOL, 900000000000L); // 0.001% = approx 3 days of mainpool feed
			
			// set up memory exchange. Initially 1GB available at 1000 per byte.
			{
				accts = addMemoryExchange(accts, MEMORY_EXCHANGE, 1000000000000L,1000000000L);
			}
			
			USER_ALLOCATION = 100000 * 1000000L; // remaining allocation to divide between initial user accounts
			
			// Set up initial peers
			for (int i = 0; i < NUM_PEERS; i++) {
				AKeyPair kp = AKeyPair.createSeeded(123454321 + i);
				KEYPAIRS[i] = kp;
				Address address = kp.getAddress();
				long peerFunds = USER_ALLOCATION / 10;

				// set a staked fund such that the first peer starts with super-majority
				long stakedFunds = (long) (((i == 0) ? 0.75 : 0.01) * peerFunds);

				// split peer funds between stake and account
				peers = addPeer(peers, address, stakedFunds);
				accts = addAccount(accts, address, peerFunds - stakedFunds);
			}
			FIRST_PEER = KEYPAIRS[0].getAddress();

			// Set up initial actor accounts
			for (int i = 0; i < NUM_USERS; i++) {
				AKeyPair kp = AKeyPair.createSeeded(543212345 + i);
				KEYPAIRS[NUM_PEERS + i] = kp;
				Address address = kp.getAddress();
				accts = addAccount(accts, address, USER_ALLOCATION / 10);
			}

			HERO_KP = KEYPAIRS[NUM_PEERS + 0];
			HERO = HERO_KP.getAddress();
			VILLAIN_KP = KEYPAIRS[NUM_PEERS + 1];
			VILLAIN = VILLAIN_KP.getAddress();

			// Build globals
			AHashMap<Symbol, Object> globals = Maps.of(Symbols.TIMESTAMP, Constants.INITIAL_TIMESTAMP, Symbols.FEES, 0L,
					Symbols.JUICE_PRICE, Constants.INITIAL_JUICE_PRICE);

			State s = State.create(accts, peers, Sets.empty(), globals, BlobMaps.empty());

			long total = s.computeTotalFunds();
			if (total != Amount.MAX_AMOUNT) throw new Error("Bad total amount: " + total);
			if (s.getPeers().size() != NUM_PEERS) throw new Error("Bad peer count: " + s.getPeers().size());
			if (s.getAccounts().size() != NUM_PEERS + NUM_USERS + NUM_GOVERNANCE+NUM_LIBRARIES) throw new Error("Bad account count");

			{ // Deploy Registry Actor to fixed Address
				Context<?> ctx = Context.createFake(s, HERO);
				Object form=Reader.readResource("actors/registry.con");
				ctx = ctx.deployActor(form,REGISTRY_ADDRESS);
				ctx.getResult();
				// Note the Registry registers itself upon creation
				s = ctx.getState();
			}
			
			{ // Deploy Trust library and register with CNS
				Context<?> ctx = Context.createFake(s, HERO);
				Object form=Reader.readResource("libraries/trust.con");
				ctx = ctx.deployActor(form, true);
				Address addr=(Address) ctx.getResult();
				ctx=ctx.eval(Reader.read("(call *registry* (cns-update 'convex.trust "+addr+"))"));
				ctx.getResult();
				// Note the Registry registers itself upon creation
				s = ctx.getState();
			}
			
			{ // Deploy Fungible library and register with CNS
				Context<?> ctx = Context.createFake(s, HERO);
				Object form=Reader.readResource("libraries/fungible.con");
				ctx = ctx.deployActor(form, true);
				Address addr=(Address) ctx.getResult();
				ctx=ctx.eval(Reader.read("(call *registry* (cns-update 'convex.fungible "+addr+"))"));
				ctx.getResult();
				// Note the Registry registers itself upon creation
				s = ctx.getState();
			}
			
			{ // Deploy Oracle Actor
				Context<?> ctx = Context.createFake(s, HERO);
				Object form=Reader.readResource("actors/oracle-trusted.con");
				ctx = ctx.deployActor(form,true);
				ORACLE_ADDRESS = (Address) ctx.getResult();
				s = register(ctx.getState(),ORACLE_ADDRESS,"Oracle Actor (default)");
			}
			
			{ // Register core library
				s = register(s,CORE_ADDRESS,"Convex Core Library");
				s = register(s,MEMORY_EXCHANGE,"Memory Exchange Pool");
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

	private static BlobMap<Address, PeerStatus> addPeer(BlobMap<Address, PeerStatus> peers, Address peerAddress,
			long initialStake) {
		Amount amount;
		amount = Amount.create(initialStake);
		PeerStatus ps = PeerStatus.create(amount, null);
		return peers.assoc(peerAddress, ps);
	}

	private static BlobMap<Address, AccountStatus> addGovernanceAccount(BlobMap<Address, AccountStatus> accts,
			Address a, long balance) {
		AccountStatus as = AccountStatus.createGovernance(balance);
		if (accts.containsKey(a)) throw new Error("Duplicate governance account!");
		accts = accts.assoc(a, as);
		return accts;
	}
	
	private static BlobMap<Address, AccountStatus> addMemoryExchange(BlobMap<Address, AccountStatus> accts,
			Address a, long balance, long allowance) {
		AccountStatus as = AccountStatus.createGovernance(balance).withAllowance(allowance);
		if (accts.containsKey(a)) throw new Error("Duplicate governance account!");
		accts = accts.assoc(a, as);
		return accts;
	}
	
	private static BlobMap<Address, AccountStatus> addCoreLibrary(BlobMap<Address, AccountStatus> accts, Address a) {

		AccountStatus as = AccountStatus.createActor(Amount.ZERO, Core.CORE_NAMESPACE);
		if (accts.containsKey(a)) throw new Error("Duplicate core library account!");
		accts = accts.assoc(a, as);
		return accts;
	}

	private static BlobMap<Address, AccountStatus> addAccount(BlobMap<Address, AccountStatus> accts, Address a,
			long balance) {
		Amount amount;
		amount = Amount.create(balance);
		AccountStatus as = AccountStatus.create(0L, amount); // zero sequence
		as=as.withAllowance(Constants.INITIAL_ACCOUNT_ALLOWANCE);
		if (accts.containsKey(a)) throw new Error("Duplicate peer account!");
		accts = accts.assoc(a, as);
		return accts;
	}

}
