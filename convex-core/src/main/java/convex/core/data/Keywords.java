package convex.core.data;

/**
 * Static Keyword values for configuration maps, records etc.
 */
public class Keywords {

	public static final Keyword STATE = Keyword.create("state");
	public static final Keyword KEYPAIR = Keyword.create("keypair");
	public static final Keyword PORT = Keyword.create("port");
	public static final Keyword ORDERS = Keyword.create("orders");
	public static final Keyword TRANSACTIONS = Keyword.create("transactions");
	public static final Keyword TIMESTAMP = Keyword.create("timestamp");
	public static final Keyword ACCOUNTS = Keyword.create("accounts");
	public static final Keyword PEERS = Keyword.create("peers");
	public static final Keyword BELIEF = Keyword.create("belief");
	public static final Keyword STATES = Keyword.create("states");
	public static final Keyword RESULTS = Keyword.create("results");
	public static final Keyword PERSIST = Keyword.create("persist");
	public static final Keyword POLL_DELAY = Keyword.create("poll-delay");


	public static final Keyword STORE = Keyword.create("store");
	public static final Keyword RESTORE = Keyword.create("restore");

	// for testing and suchlike
	public static final Keyword FOO = Keyword.create("foo");
	public static final Keyword BAR = Keyword.create("bar");
	public static final Keyword BAZ = Keyword.create("baz");


	public static final Keyword SALT = Keyword.create("salt");
	public static final Keyword IV = Keyword.create("iv");
	public static final Keyword ROUNDS = Keyword.create("rounds");
	public static final Keyword CIPHERTEXT = Keyword.create("ciphertext");
	public static final Keyword GLOBALS = Keyword.create("globals");
	public static final Keyword SCHEDULE = Keyword.create("schedule");

	public static final Keyword NAME = Keyword.create("name");

	// source info
	public static final Keyword START = Keyword.create("start");
	public static final Keyword END = Keyword.create("end");
	public static final Keyword SOURCE = Keyword.create("source");
	public static final Keyword TAG = Keyword.create("tag");

	public static final Keyword DESCRIPTION = Keyword.create("description");
	public static final Keyword EXAMPLES = Keyword.create("examples");
	public static final Keyword CODE = Keyword.create("code");

	public static final Keyword TYPE = Keyword.create("type");

	public static final Keyword PEER = Keyword.create("peer");
	public static final Keyword STAKE = Keyword.create("stake");
	public static final Keyword STAKES = Keyword.create("stakes");
	public static final Keyword DELEGATED_STAKE = Keyword.create("delegated-stake");
	public static final Keyword OWNER = Keyword.create("owner");

	public static final Keyword BIND_ADDRESS = Keyword.create("bind-address");
	public static final Keyword URL = Keyword.create("url");

	// Account record keys
	public static final Keyword SEQUENCE = Keyword.create("sequence");
	public static final Keyword KEY = Keyword.create("key");
	public static final Keyword BALANCE = Keyword.create("balance");
	public static final Keyword ALLOWANCE = Keyword.create("allowance");
	public static final Keyword HOLDINGS = Keyword.create("holdings");
	public static final Keyword CONTROLLER = Keyword.create("controller");
	public static final Keyword ENVIRONMENT = Keyword.create("environment");
	public static final Keyword METADATA = Keyword.create("metadata");
	public static final Keyword PARENT = Keyword.create("parent");

	// Result keywords
	public static final Keyword ID = Keyword.create("id");
	public static final Keyword TX = Keyword.create("tx");
	public static final Keyword LOC = Keyword.create("loc");
	public static final Keyword RESULT = Keyword.create("result");
	public static final Keyword ERROR = Keyword.create("error");
	public static final Keyword ADDRESS = Keyword.create("address");
	public static final Keyword ERROR_CODE = Keyword.create("error-code");
	public static final Keyword TRACE = Keyword.create("trace");
	public static final Keyword INFO = Keyword.create("info");
	public static final Keyword EADDR = Keyword.create("eaddr");
	public static final Keyword MEM = Keyword.create("mem");
	public static final Keyword FEES = Keyword.create("fees");
	public static final Keyword JUICE = Keyword.create("juice");
	public static final Keyword LOG = Keyword.create("log");
	
	public static final Keyword CVM = Keyword.create("cvm");

	// Metadata keywords
	public static final Keyword EXPANDER_META = Keyword.create("expander");
	public static final Keyword MACRO_META = Keyword.create("macro");
	public static final Keyword CALLABLE_META = Keyword.create("callable");
	public static final Keyword PRIVATE_META = Keyword.create("private");
	public static final Keyword DOC_META = Keyword.create("doc");
	public static final Keyword SPECIAL_META = Keyword.create("special");


	public static final Keyword VALUE = Keyword.create("value");
	public static final Keyword FUNCTION = Keyword.create("function");


	public static final Keyword OUTGOING_CONNECTIONS = Keyword.create("outgoing-connections");
	public static final Keyword AUTO_MANAGE = Keyword.create("auto-manage");
	public static final Keyword TIMEOUT = Keyword.create("timeout");
	public static final Keyword EVENT_HOOK = Keyword.create("event-hook");
	public static final Keyword STATIC = Keyword.create("static");
	
	public static final Keyword PUBLIC_KEY = Keyword.create("public-key");
	public static final Keyword SIGNATURE = Keyword.create("signature");
	
	public static final Keyword TXS = Keyword.create("txs");
	public static final Keyword MODE = Keyword.create("mode");
	
	public static final Keyword AMOUNT = Keyword.create("amount");
	public static final Keyword CALL = Keyword.create("call");
	public static final Keyword COMMAND = Keyword.create("command");
	public static final Keyword OFFER = Keyword.create("offer");
	public static final Keyword ORIGIN = Keyword.create("origin");
	public static final Keyword TARGET = Keyword.create("target");

	public static final Keyword BLOCKS = Keyword.create("blocks");
	public static final Keyword CONSENSUS_POINT = Keyword.create("consensus-point");
	public static final Keyword CONSENSUS = Keyword.create("consensus");
	public static final Keyword PROPOSAL_POINT = Keyword.create("proposal-point");

	public static final Keyword ROOT_KEY = Keyword.create("root-key");
	public static final Keyword POSITION = Keyword.create("position");
	public static final Keyword GENESIS = Keyword.create("genesis");
	public static final Keyword HISTORY = Keyword.create("history");
	public static final Keyword ORDER = Keyword.create("order");
	
	// key store stuff
	public static final Keyword KEYSTORE = Keyword.create("keystore");
	public static final Keyword STOREPASS = Keyword.create("storepass");

	
	public static final Keyword MIN_BLOCK_TIME = Keyword.create("min-block-time");
	
	// Commond trust keys
	public static final Keyword CONTROL = Keyword.create("control");




}
