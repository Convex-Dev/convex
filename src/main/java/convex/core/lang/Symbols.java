package convex.core.lang;

import convex.core.data.Symbol;

/**
 * Static class for Symbol constants.
 * 
 * "If you have more things than names, your design is broken" - Stuart Halloway
 */
public class Symbols {
	public static final Symbol COUNT = Symbol.create("count");
	public static final Symbol CONJ = Symbol.create("conj");
	public static final Symbol CONS = Symbol.create("cons");
	public static final Symbol GET = Symbol.create("get");
	public static final Symbol GET_IN = Symbol.create("get-in");
	public static final Symbol ASSOC = Symbol.create("assoc");
	public static final Symbol ASSOC_IN = Symbol.create("assoc-in");
	public static final Symbol DISSOC = Symbol.create("dissoc");
	public static final Symbol DISJ = Symbol.create("disj");
	public static final Symbol NTH = Symbol.create("nth");

	public static final Symbol VECTOR = Symbol.create("vector");
	public static final Symbol VEC = Symbol.create("vec");
	public static final Symbol SET = Symbol.create("set");
	public static final Symbol HASH_MAP = Symbol.create("hash-map");
	public static final Symbol HASH_SET = Symbol.create("hash-set");
	public static final Symbol LIST = Symbol.create("list");
	public static final Symbol EMPTY = Symbol.create("empty");
	public static final Symbol INTO = Symbol.create("into");

	public static final Symbol CONCAT = Symbol.create("concat");
	public static final Symbol MAP = Symbol.create("map");
	public static final Symbol REDUCE = Symbol.create("reduce");

	public static final Symbol KEYS = Symbol.create("keys");
	public static final Symbol VALUES = Symbol.create("values");

	public static final Symbol STR = Symbol.create("str");
	public static final Symbol KEYWORD = Symbol.create("keyword");
	public static final Symbol SYMBOL = Symbol.create("symbol");
	public static final Symbol NAME = Symbol.create("name");

	public static final Symbol EQUALS = Symbol.create("=");

	public static final Symbol EQ = Symbol.create("==");
	public static final Symbol LT = Symbol.create("<");
	public static final Symbol GT = Symbol.create(">");
	public static final Symbol LE = Symbol.create("<=");
	public static final Symbol GE = Symbol.create(">=");

	public static final Symbol NOT = Symbol.create("not");
	public static final Symbol OR = Symbol.create("or");
	public static final Symbol AND = Symbol.create("and");

	public static final Symbol ASSERT = Symbol.create("assert");
	public static final Symbol FAIL = Symbol.create("fail");
	public static final Symbol TRY = Symbol.create("try");
	public static final Symbol CATCH = Symbol.create("catch");

	public static final Symbol APPLY = Symbol.create("apply");
	public static final Symbol HASH = Symbol.create("hash");

	public static final Symbol QUOTE = Symbol.create("quote");
	public static final Symbol SYNTAX_QUOTE = Symbol.create("syntax-quote");
	public static final Symbol UNQUOTE = Symbol.create("unquote");
	public static final Symbol UNQUOTE_SPLICING = Symbol.create("unquote-splicing");

	public static final Symbol DO = Symbol.create("do");
	public static final Symbol COND = Symbol.create("cond");
	public static final Symbol DEF = Symbol.create("def");
	public static final Symbol UNDEF = Symbol.create("undef");
	public static final Symbol UNDEF_STAR = Symbol.create("undef*");

	public static final Symbol FN = Symbol.create("fn");
	public static final Symbol MACRO = Symbol.create("macro");
	public static final Symbol EXPANDER = Symbol.create("expander");

	public static final Symbol IF = Symbol.create("if");
	public static final Symbol WHEN = Symbol.create("when");
	public static final Symbol LET = Symbol.create("let");

	public static final Symbol STORE = Symbol.create("store");
	public static final Symbol FETCH = Symbol.create("fetch");

	public static final Symbol ADDRESS = Symbol.create("address");
	public static final Symbol BALANCE = Symbol.create("balance");
	public static final Symbol TRANSFER = Symbol.create("transfer");
	public static final Symbol ACCEPT = Symbol.create("accept");

	public static final Symbol STAKE = Symbol.create("stake");
	public static final Symbol CREATE_PEER = Symbol.create("create-peer");

	public static final Symbol CALL = Symbol.create("call");
	public static final Symbol CALL_STAR = Symbol.create("call*");

	public static final Symbol HALT = Symbol.create("halt");
	public static final Symbol ROLLBACK = Symbol.create("rollback");

	public static final Symbol EXPORT = Symbol.create("export");
	public static final Symbol EXPORTS_Q = Symbol.create("exports?");
	public static final Symbol DEPLOY = Symbol.create("deploy");
	public static final Symbol DEPLOY_ONCE = Symbol.create("deploy-once");
	
	public static final Symbol BLOB = Symbol.create("blob");

	public static final Symbol INC = Symbol.create("inc");
	public static final Symbol DEC = Symbol.create("dec");

	public static final Symbol LONG = Symbol.create("long");
	public static final Symbol BYTE = Symbol.create("byte");
	public static final Symbol SHORT = Symbol.create("short");
	public static final Symbol INT = Symbol.create("int");
	public static final Symbol CHAR = Symbol.create("char");

	public static final Symbol DOUBLE = Symbol.create("double");

	public static final Symbol BOOLEAN = Symbol.create("boolean");
	public static final Symbol BOOLEAN_Q = Symbol.create("boolean?");
	
	public static final Symbol PLUS = Symbol.create("+");
	public static final Symbol MINUS = Symbol.create("-");
	public static final Symbol TIMES = Symbol.create("*");
	public static final Symbol DIVIDE = Symbol.create("/");

	public static final Symbol SQRT = Symbol.create("sqrt");
	public static final Symbol EXP = Symbol.create("exp");
	public static final Symbol POW = Symbol.create("pow");

	public static final Symbol NAN = Symbol.create("NaN");

	public static final Symbol LOOP = Symbol.create("loop");
	public static final Symbol RECUR = Symbol.create("recur");
	public static final Symbol RETURN = Symbol.create("return");
	public static final Symbol BREAK = Symbol.create("break");

	public static final char SPECIAL_STAR = '*';
	public static final Symbol STAR_ADDRESS = Symbol.create("*address*");
	public static final Symbol STAR_ALLOWANCE = Symbol.create("*allowance*");
	public static final Symbol STAR_CALLER = Symbol.create("*caller*");
	public static final Symbol STAR_ORIGIN = Symbol.create("*origin*");
	public static final Symbol STAR_JUICE = Symbol.create("*juice*");
	public static final Symbol STAR_BALANCE = Symbol.create("*balance*");
	public static final Symbol STAR_DEPTH = Symbol.create("*depth*");
	public static final Symbol STAR_RESULT = Symbol.create("*result*");
	public static final Symbol STAR_EXPORTS = Symbol.create("*exports*");
	public static final Symbol STAR_TIMESTAMP = Symbol.create("*timestamp*");
	public static final Symbol STAR_OFFER = Symbol.create("*offer*");
	public static final Symbol STAR_STATE = Symbol.create("*state*");
	public static final Symbol STAR_HOLDINGS = Symbol.create("*holdings*");

	public static final Symbol STAR_ALIASES = Symbol.create("*aliases*");
	
	public static final Symbol STAR_INITIAL_EXPANDER = Symbol.create("*initial-expander*");

	public static final Symbol HERO = Symbol.create("hero");

	public static final Symbol COMPILE = Symbol.create("compile");
	public static final Symbol READ = Symbol.create("read");
	public static final Symbol EVAL = Symbol.create("eval");
	public static final Symbol EXPAND = Symbol.create("expand");

	public static final Symbol SCHEDULE = Symbol.create("schedule");
	public static final Symbol SCHEDULE_STAR = Symbol.create("schedule*");

	public static final Symbol FIRST = Symbol.create("first");
	public static final Symbol SECOND = Symbol.create("second");
	public static final Symbol LAST = Symbol.create("last");
	public static final Symbol NEXT = Symbol.create("next");

	public static final Symbol AMPERSAND = Symbol.create("&");
	public static final Symbol UNDERSCORE = Symbol.create("_");

	public static final Symbol X = Symbol.create("x");
	public static final Symbol E = Symbol.create("e");

	public static final Symbol NIL_Q = Symbol.create("nil?");
	public static final Symbol LIST_Q = Symbol.create("list?");
	public static final Symbol VECTOR_Q = Symbol.create("vector?");
	public static final Symbol SET_Q = Symbol.create("set?");
	public static final Symbol MAP_Q = Symbol.create("map?");

	public static final Symbol COLL_Q = Symbol.create("coll?");
	public static final Symbol EMPTY_Q = Symbol.create("empty?");

	public static final Symbol SYMBOL_Q = Symbol.create("symbol?");
	public static final Symbol KEYWORD_Q = Symbol.create("keyword?");
	public static final Symbol BLOB_Q = Symbol.create("blob?");
	public static final Symbol ADDRESS_Q = Symbol.create("address?");
	public static final Symbol LONG_Q = Symbol.create("long?");
	public static final Symbol STR_Q = Symbol.create("str?");
	public static final Symbol NUMBER_Q = Symbol.create("number?");
	public static final Symbol HASH_Q = Symbol.create("hash?");

	public static final Symbol FN_Q = Symbol.create("fn?");
	public static final Symbol ACTOR_Q = Symbol.create("actor?");

	public static final Symbol ZERO_Q = Symbol.create("zero?");

	public static final Symbol CONTAINS_KEY_Q = Symbol.create("contains-key?");

	public static final Symbol FOO = Symbol.create("foo");
	public static final Symbol BAR = Symbol.create("bar");

	public static final Symbol LOOKUP = Symbol.create("lookup");

	// State global fields
	public static final Symbol TIMESTAMP = Symbol.create("timestamp");
	public static final Symbol JUICE_PRICE = Symbol.create("juice-price");
	public static final Symbol FEES = Symbol.create("fees");

	// source info
	public static final Symbol START = Symbol.create("start");
	public static final Symbol END = Symbol.create("end");
	public static final Symbol SOURCE = Symbol.create("source");

	public static final Symbol SYNTAX_Q = Symbol.create("syntax?");
	public static final Symbol SYNTAX = Symbol.create("syntax");
	public static final Symbol GET_META = Symbol.create("get-meta");
	public static final Symbol UNSYNTAX = Symbol.create("unsyntax");
	
	public static final Symbol DOC = Symbol.create("doc");
	public static final Symbol META = Symbol.create("meta");
	public static final Symbol META_STAR = Symbol.create("*meta*");
	
	public static final Symbol LOOKUP_SYNTAX = Symbol.create("lookup-syntax");

	public static final Symbol GET_HOLDING = Symbol.create("get-holding");
	public static final Symbol SET_HOLDING = Symbol.create("set-holding");

	public static final Symbol SET_BANG = Symbol.create("set!");
	public static final Symbol SET_STAR = Symbol.create("set*");
	
	public static final Symbol REGISTER = Symbol.create("register");
	


}
