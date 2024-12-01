package convex.core.cvm;

import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.data.Symbol;

/**
 * Static class for Symbol constants.
 *
 * "If you have more things than names, your design is broken" - Stuart Halloway
 */
public class Symbols {
	public static final Symbol COUNT = intern("count");
	public static final Symbol CONJ = intern("conj");
	public static final Symbol CONS = intern("cons");
	public static final Symbol GET = intern("get");
	public static final Symbol GET_IN = intern("get-in");
	public static final Symbol ASSOC = intern("assoc");
	public static final Symbol ASSOC_IN = intern("assoc-in");
	public static final Symbol DISSOC = intern("dissoc");
	public static final Symbol DISJ = intern("disj");
	public static final Symbol NTH = intern("nth");

	public static final Symbol VECTOR = intern("vector");
	public static final Symbol VEC = intern("vec");
	public static final Symbol SET = intern("set");
	public static final Symbol HASH_MAP = intern("hash-map");
	public static final Symbol INDEX = intern("index");
	public static final Symbol HASH_SET = intern("hash-set");
	public static final Symbol LIST = intern("list");
	public static final Symbol EMPTY = intern("empty");
	public static final Symbol INTO = intern("into");

	public static final Symbol CONCAT = intern("concat");
	public static final Symbol MAP = intern("map");
	public static final Symbol REDUCE = intern("reduce");

	public static final Symbol KEYS = intern("keys");
	public static final Symbol VALUES = intern("values");

	public static final Symbol STR = intern("str");
	public static final Symbol KEYWORD = intern("keyword");
	public static final Symbol SYMBOL = intern("symbol");
	public static final Symbol NAME = intern("name");
	public static final Symbol RESOLVE = intern("resolve");

	public static final Symbol EQUALS = intern("=");

	public static final Symbol EQ = intern("==");
	public static final Symbol LT = intern("<");
	public static final Symbol GT = intern(">");
	public static final Symbol LE = intern("<=");
	public static final Symbol GE = intern(">=");
	public static final Symbol NE = intern("!=");
	
	public static final Symbol MIN = intern("min");
	public static final Symbol MAX = intern("max");

	public static final Symbol NOT = intern("not");
	public static final Symbol OR = intern("or");
	public static final Symbol AND = intern("and");

	public static final Symbol ASSERT = intern("assert");
	public static final Symbol FAIL = intern("fail");
	public static final Symbol TRY = intern("try");
	public static final Symbol CATCH = intern("catch");

	public static final Symbol APPLY = intern("apply");
	
	public static final Symbol HASH = intern("hash");
	public static final Symbol KECCAK256 = intern("keccak256");
	public static final Symbol SHA256 = intern("sha256");

	public static final Symbol QUOTE = intern("quote");
	public static final Symbol QUASIQUOTE = intern("quasiquote");

	public static final Symbol SYNTAX_QUOTE = intern("syntax-quote");
	public static final Symbol UNQUOTE = intern("unquote");
	public static final Symbol UNQUOTE_SPLICING = intern("unquote-splicing");

	public static final Symbol DO = intern("do");
	public static final Symbol COND = intern("cond");
	public static final Symbol DEF = intern("def");
	public static final Symbol UNDEF = intern("undef");
	public static final Symbol UNDEF_STAR = intern("undef*");

	public static final Symbol FN = intern("fn");
	public static final Symbol MACRO = intern("macro");
	public static final Symbol EXPANDER = intern("expander");

	public static final Symbol IF = intern("if");
	public static final Symbol WHEN = intern("when");
	public static final Symbol LET = intern("let");

	public static final Symbol STORE = intern("store");
	public static final Symbol FETCH = intern("fetch");

	public static final Symbol ADDRESS = intern("address");
	public static final Symbol BALANCE = intern("balance");
	public static final Symbol TRANSFER = intern("transfer");
	public static final Symbol ACCEPT = intern("accept");
	public static final Symbol ACCOUNT = intern("account");

	public static final Symbol ACCOUNT_Q = intern("account?");

	public static final Symbol SET_STAKE = intern("set-stake");
	public static final Symbol CREATE_PEER = intern("create-peer");
	public static final Symbol SET_PEER_DATA = intern("set-peer-data");
	public static final Symbol SET_PEER_STAKE = intern("set-peer-stake");
	public static final Symbol EVICT_PEER = intern("evict-peer");
	
	public static final Symbol GET_STAKE = intern("get-stake");
	public static final Symbol GET_PEER_STAKE = intern("get-peer-stake");

	public static final Symbol CALL = intern("call");
	public static final Symbol CALL_STAR = intern("call*");

	public static final Symbol HALT = intern("halt");
	public static final Symbol ROLLBACK = intern("rollback");

	public static final Symbol CALLABLE_Q = intern("callable?");
	public static final Symbol DEPLOY = intern("deploy");
	public static final Symbol DEPLOY_ONCE = intern("deploy-once");

	public static final Symbol BLOB = intern("blob");

	public static final Symbol INC = intern("inc");
	public static final Symbol DEC = intern("dec");

	public static final Symbol LONG = intern("long");
	public static final Symbol BYTE = intern("byte");
	public static final Symbol CHAR = intern("char");
	
	public static final Symbol INT = intern("int");
	public static final Symbol INT_Q = intern("int?");


	public static final Symbol DOUBLE = intern("double");

	public static final Symbol BOOLEAN = intern("boolean");
	public static final Symbol BOOLEAN_Q = intern("boolean?");

	public static final Symbol PLUS = intern("+");
	public static final Symbol MINUS = intern("-");
	public static final Symbol TIMES = intern("*");
	public static final Symbol DIVIDE = intern("/");

	public static final Symbol ABS = intern("abs");
	public static final Symbol SIGNUM = intern("signum");
	public static final Symbol SQRT = intern("sqrt");
	public static final Symbol EXP = intern("exp");
	public static final Symbol POW = intern("pow");
	public static final Symbol DIV = intern("div");
	public static final Symbol MOD = intern("mod");
	public static final Symbol QUOT = intern("quot");
	public static final Symbol REM = intern("rem");
	public static final Symbol EXPT = intern("expt");


	public static final Symbol FLOOR = intern("floor");
	public static final Symbol CEIL = intern("ceil");

	public static final Symbol NAN = intern("NaN");

	public static final Symbol LOOP = intern("loop");
	public static final Symbol RECUR = intern("recur");
	public static final Symbol TAILCALL_STAR = intern("tailcall*");

	public static final Symbol RETURN = intern("return");
	public static final Symbol BREAK = intern("break");
	public static final Symbol REDUCED = intern("reduced");


	public static final char SPECIAL_STAR = '*';
	public static final Symbol STAR_ADDRESS = intern("*address*");
	public static final Symbol STAR_MEMORY = intern("*memory*");
	public static final Symbol STAR_CALLER = intern("*caller*");
	public static final Symbol STAR_ORIGIN = intern("*origin*");
	public static final Symbol STAR_CONTROLLER = intern("*controller*");
	public static final Symbol STAR_JUICE = intern("*juice*");
	public static final Symbol STAR_JUICE_PRICE = intern("*juice-price*");
	public static final Symbol STAR_JUICE_LIMIT = intern("*juice-limit*");
	public static final Symbol STAR_BALANCE = intern("*balance*");
	public static final Symbol STAR_DEPTH = intern("*depth*");
	public static final Symbol STAR_RESULT = intern("*result*");
	public static final Symbol STAR_TIMESTAMP = intern("*timestamp*");
	public static final Symbol STAR_OFFER = intern("*offer*");
	public static final Symbol STAR_STATE = intern("*state*");
	public static final Symbol STAR_HOLDINGS = intern("*holdings*");
	public static final Symbol STAR_SEQUENCE = intern("*sequence*");
	public static final Symbol STAR_KEY = intern("*key*");
	public static final Symbol STAR_SCOPE = intern("*scope*");
	public static final Symbol STAR_ENV= intern("*env*");
	public static final Symbol STAR_PARENT = intern("*parent*");
	public static final Symbol STAR_NOP = intern("*nop*");
	public static final Symbol STAR_MEMORY_PRICE = intern("*memory-price*");
	public static final Symbol STAR_SIGNER = intern("*signer*");
	public static final Symbol STAR_PEER = intern("*peer*");

	public static final Symbol STAR_LANG = intern("*lang*");

	public static final Symbol STAR_INITIAL_EXPANDER = intern("*initial-expander*");

	public static final Symbol HERO = intern("hero");

	public static final Symbol COMPILE = intern("compile");
	public static final Symbol READ = intern("read");
	public static final Symbol EVAL = intern("eval");
	public static final Symbol EVAL_AS = intern("eval-as");

	public static final Symbol QUERY = intern("query");
	public static final Symbol QUERY_AS = intern("query-as");


	public static final Symbol EXPAND = intern("expand");

	public static final Symbol SCHEDULE = intern("schedule");
	public static final Symbol SCHEDULE_STAR = intern("schedule*");

	public static final Symbol FIRST = intern("first");
	public static final Symbol SECOND = intern("second");
	public static final Symbol LAST = intern("last");
	public static final Symbol NEXT = intern("next");
	public static final Symbol REVERSE = intern("reverse");
	public static final Symbol SLICE = intern("slice");

	public static final Symbol AMPERSAND = intern("&");
	public static final Symbol UNDERSCORE = intern("_");

	public static final Symbol X = intern("x");
	public static final Symbol E = intern("e");

	public static final Symbol NIL = intern("nil");

	public static final Symbol NIL_Q = intern("nil?");
	public static final Symbol LIST_Q = intern("list?");
	public static final Symbol VECTOR_Q = intern("vector?");
	public static final Symbol SET_Q = intern("set?");
	public static final Symbol MAP_Q = intern("map?");

	public static final Symbol COLL_Q = intern("coll?");
	public static final Symbol EMPTY_Q = intern("empty?");

	public static final Symbol SYMBOL_Q = intern("symbol?");
	public static final Symbol KEYWORD_Q = intern("keyword?");
	public static final Symbol BLOB_Q = intern("blob?");
	public static final Symbol ADDRESS_Q = intern("address?");
	public static final Symbol LONG_Q = intern("long?");
	public static final Symbol DOUBLE_Q = intern("double?");
	public static final Symbol STR_Q = intern("str?");
	public static final Symbol NUMBER_Q = intern("number?");
	public static final Symbol HASH_Q = intern("hash?");
	public static final Symbol COUNTABLE_Q = intern("countable?");

	public static final Symbol FN_Q = intern("fn?");
	public static final Symbol ACTOR_Q = intern("actor?");

	public static final Symbol ZERO_Q = intern("zero?");

	public static final Symbol CONTAINS_KEY_Q = intern("contains-key?");

	public static final Symbol FOO = intern("foo");
	public static final Symbol BAR = intern("bar");
	public static final Symbol BAZ = intern("baz");;


	public static final Symbol LOOKUP = intern("lookup");

	// State global fields
	public static final Symbol TIMESTAMP = intern("timestamp");
	public static final Symbol JUICE_PRICE = intern("juice-price");
	public static final Symbol FEES = intern("fees");

	// source info
	public static final Symbol START = intern("start");
	public static final Symbol END = intern("end");
	public static final Symbol SOURCE = intern("source");

	public static final Symbol SYNTAX_Q = intern("syntax?");
	public static final Symbol SYNTAX = intern("syntax");
	public static final Symbol GET_META = intern("get-meta");
	public static final Symbol UNSYNTAX = intern("unsyntax");

	public static final Symbol DOC = intern("doc");
	public static final Symbol META = intern("meta");
	public static final Symbol META_STAR = intern("*meta*");

	public static final Symbol LOOKUP_META = intern("lookup-meta");

	public static final Symbol GET_HOLDING = intern("get-holding");
	public static final Symbol SET_HOLDING = intern("set-holding");

	public static final Symbol GET_CONTROLLER = intern("get-controller");
	public static final Symbol SET_CONTROLLER = intern("set-controller");
	
	public static final Symbol SET_PARENT = intern("set-parent");

	
	public static final Symbol CHECK_TRUSTED_Q = intern("check-trusted?");

	public static final Symbol SET_MEMORY = intern("set-memory");
	public static final Symbol TRANSFER_MEMORY = intern("transfer-memory");

	public static final Symbol RECEIVE_ALLOWANCE = intern("receive-allowance");
	public static final Symbol RECEIVE_COIN = intern("receive-coin");
	public static final Symbol RECEIVE_ASSET = intern("receive-asset");


	public static final Symbol SET_BANG = intern("set!");
	public static final Symbol SET_STAR = intern("set*");

	public static final Symbol REGISTER = intern("register");

	public static final Symbol SUBSET_Q = intern("subset?");
	public static final Symbol UNION = intern("union");
	public static final Symbol INTERSECTION = intern("intersection");
	public static final Symbol DIFFERENCE = intern("difference");

	public static final Symbol MERGE = intern("merge");

	public static final Symbol ENCODING = intern("encoding");

	public static final Symbol CREATE_ACCOUNT = intern("create-account");
	public static final Symbol SET_KEY = intern("set-key");

	public static final Symbol LOG = intern("log");

	public static final Symbol CNS_RESOLVE = intern("cns-resolve");
	public static final Symbol CNS_UPDATE = intern("cns-update");

	public static final Symbol NAN_Q = intern("nan?");

	public static final Symbol BIT_AND = intern("bit-and");
	public static final Symbol BIT_OR = intern("bit-or");
	public static final Symbol BIT_XOR = intern("bit-xor");
	public static final Symbol BIT_NOT = intern("bit-not");
	
	public static final Symbol STATIC = intern("static");
	
	public static final Symbol PRINT = intern("print");
	public static final Symbol SPLIT = intern("split");
	public static final Symbol JOIN = intern("join");
	
	public static final Symbol TORUS = intern("torus");
	
	public static final Symbol MEMORY = intern("memory");
	public static final Symbol MEMORY_VALUE = intern("memory-value");
	public static final Symbol PROTOCOL = intern("protocol");
	public static final Symbol CREATE = intern("create");




	
	public static Symbol intern(String s) {
		AString name=Strings.create(s);
		Symbol sym=Symbol.intern(name);
		return sym;
	}


}
