package convex.core.cvm;

import convex.core.data.Keyword;

/**
 * Static intenral Keyword values for configuration maps, records etc.
 */
public class Keywords {

	public static final Keyword STATE = Keyword.intern("state");
	public static final Keyword KEYPAIR = Keyword.intern("keypair");
	public static final Keyword PORT = Keyword.intern("port");
	public static final Keyword ORDERS = Keyword.intern("orders");
	public static final Keyword TRANSACTIONS = Keyword.intern("transactions");
	public static final Keyword TIMESTAMP = Keyword.intern("timestamp");
	public static final Keyword ACCOUNTS = Keyword.intern("accounts");
	public static final Keyword PEERS = Keyword.intern("peers");
	public static final Keyword BELIEF = Keyword.intern("belief");
	public static final Keyword STATES = Keyword.intern("states");
	public static final Keyword RESULTS = Keyword.intern("results");
	public static final Keyword PERSIST = Keyword.intern("persist");
	public static final Keyword POLL_DELAY = Keyword.intern("poll-delay");


	public static final Keyword STORE = Keyword.intern("store");
	public static final Keyword RESTORE = Keyword.intern("restore");

	// for testing and suchlike
	public static final Keyword FOO = Keyword.intern("foo");
	public static final Keyword BAR = Keyword.intern("bar");
	public static final Keyword BAZ = Keyword.intern("baz");


	public static final Keyword SALT = Keyword.intern("salt");
	public static final Keyword IV = Keyword.intern("iv");
	public static final Keyword ROUNDS = Keyword.intern("rounds");
	public static final Keyword CIPHERTEXT = Keyword.intern("ciphertext");
	public static final Keyword GLOBALS = Keyword.intern("globals");
	public static final Keyword SCHEDULE = Keyword.intern("schedule");

	public static final Keyword NAME = Keyword.intern("name");

	// source info
	public static final Keyword START = Keyword.intern("start");
	public static final Keyword END = Keyword.intern("end");
	public static final Keyword SOURCE = Keyword.intern("source");
	public static final Keyword TAG = Keyword.intern("tag");

	public static final Keyword DESCRIPTION = Keyword.intern("description");
	public static final Keyword EXAMPLES = Keyword.intern("examples");
	public static final Keyword CODE = Keyword.intern("code");

	public static final Keyword TYPE = Keyword.intern("type");

	public static final Keyword PEER = Keyword.intern("peer");
	public static final Keyword STAKE = Keyword.intern("stake");
	public static final Keyword STAKES = Keyword.intern("stakes");
	public static final Keyword DELEGATED_STAKE = Keyword.intern("delegated-stake");
	public static final Keyword OWNER = Keyword.intern("owner");

	public static final Keyword BIND_ADDRESS = Keyword.intern("bind-address");
	public static final Keyword URL = Keyword.intern("url");

	// Account record keys
	public static final Keyword SEQUENCE = Keyword.intern("sequence");
	public static final Keyword KEY = Keyword.intern("key");
	public static final Keyword BALANCE = Keyword.intern("balance");
	public static final Keyword ALLOWANCE = Keyword.intern("allowance");
	public static final Keyword HOLDINGS = Keyword.intern("holdings");
	public static final Keyword CONTROLLER = Keyword.intern("controller");
	public static final Keyword ENVIRONMENT = Keyword.intern("environment");
	public static final Keyword METADATA = Keyword.intern("metadata");
	public static final Keyword PARENT = Keyword.intern("parent");

	// Result keywords
	public static final Keyword ID = Keyword.intern("id");
	public static final Keyword TX = Keyword.intern("tx");
	public static final Keyword LOC = Keyword.intern("loc");
	public static final Keyword RESULT = Keyword.intern("result");
	public static final Keyword ERROR = Keyword.intern("error");
	public static final Keyword ADDRESS = Keyword.intern("address");
	public static final Keyword ERROR_CODE = Keyword.intern("error-code");
	public static final Keyword TRACE = Keyword.intern("trace");
	public static final Keyword INFO = Keyword.intern("info");
	public static final Keyword EADDR = Keyword.intern("eaddr");
	public static final Keyword MEM = Keyword.intern("mem");
	public static final Keyword FEES = Keyword.intern("fees");
	public static final Keyword JUICE = Keyword.intern("juice");
	public static final Keyword LOG = Keyword.intern("log");
	
	public static final Keyword CVM = Keyword.intern("cvm");

	// Metadata keywords
	public static final Keyword EXPANDER_META = Keyword.intern("expander");
	public static final Keyword MACRO_META = Keyword.intern("macro");
	public static final Keyword CALLABLE_META = Keyword.intern("callable");
	public static final Keyword PRIVATE_META = Keyword.intern("private");
	public static final Keyword DOC_META = Keyword.intern("doc");
	public static final Keyword SPECIAL_META = Keyword.intern("special");


	public static final Keyword VALUE = Keyword.intern("value");
	public static final Keyword FUNCTION = Keyword.intern("function");


	public static final Keyword OUTGOING_CONNECTIONS = Keyword.intern("outgoing-connections");
	public static final Keyword AUTO_MANAGE = Keyword.intern("auto-manage");
	public static final Keyword TIMEOUT = Keyword.intern("timeout");
	public static final Keyword EVENT_HOOK = Keyword.intern("event-hook");
	public static final Keyword STATIC = Keyword.intern("static");
	
	public static final Keyword PUBLIC_KEY = Keyword.intern("public-key");
	public static final Keyword SIGNATURE = Keyword.intern("signature");
	
	public static final Keyword TXS = Keyword.intern("txs");
	public static final Keyword MODE = Keyword.intern("mode");
	
	public static final Keyword AMOUNT = Keyword.intern("amount");
	public static final Keyword CALL = Keyword.intern("call");
	public static final Keyword COMMAND = Keyword.intern("command");
	public static final Keyword OFFER = Keyword.intern("offer");
	public static final Keyword ORIGIN = Keyword.intern("origin");
	public static final Keyword TARGET = Keyword.intern("target");
	public static final Keyword ARGS = Keyword.intern("args");


	public static final Keyword BLOCKS = Keyword.intern("blocks");
	public static final Keyword CONSENSUS_POINT = Keyword.intern("consensus-point");
	public static final Keyword CONSENSUS = Keyword.intern("consensus");
	public static final Keyword PROPOSAL_POINT = Keyword.intern("proposal-point");

	public static final Keyword ROOT_KEY = Keyword.intern("root-key");
	public static final Keyword POSITION = Keyword.intern("position");
	public static final Keyword GENESIS = Keyword.intern("genesis");
	public static final Keyword HISTORY = Keyword.intern("history");
	public static final Keyword ORDER = Keyword.intern("order");
	
	// key store stuff
	public static final Keyword KEYSTORE = Keyword.intern("keystore");
	public static final Keyword STOREPASS = Keyword.intern("storepass");

	
	public static final Keyword MIN_BLOCK_TIME = Keyword.intern("min-block-time");
	
	// Commond trust keys
	public static final Keyword CONTROL = Keyword.intern("control");




}
