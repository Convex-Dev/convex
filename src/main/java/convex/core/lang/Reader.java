package convex.core.lang;

import java.io.IOException;
import java.util.ArrayList;

import org.parboiled.Action;
import org.parboiled.BaseParser;
import org.parboiled.Context;
import org.parboiled.Parboiled;
import org.parboiled.Rule;
import org.parboiled.annotations.BuildParseTree;
import org.parboiled.buffers.InputBuffer;
import org.parboiled.errors.ParseError;
import org.parboiled.errors.ParserRuntimeException;
import org.parboiled.parserunners.ParseRunner;
import org.parboiled.parserunners.ReportingParseRunner;
import org.parboiled.support.IndexRange;
import org.parboiled.support.ParsingResult;
import org.parboiled.support.StringVar;
import org.parboiled.support.Var;

import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AList;
import convex.core.data.AMap;
import convex.core.data.ASequence;
import convex.core.data.Address;
import convex.core.data.Blob;
import convex.core.data.Keyword;
import convex.core.data.Keywords;
import convex.core.data.Lists;
import convex.core.data.Maps;
import convex.core.data.Sets;
import convex.core.data.Strings;
import convex.core.data.Symbol;
import convex.core.data.Syntax;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMChar;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.exceptions.ParseException;
import convex.core.util.Utils;

//@formatter:off

/**
 * Parboiled Parser implementation which reads source code and produces a tree
 * of parsed objects.
 * 
 * Supports reading in either raw mode or wrapping with Syntax Objects. The
 * latter is required for source references etc.
 *
 * "Talk is cheap. Show me the code." - Linus Torvalds
 */
@BuildParseTree
public class Reader extends BaseParser<ACell> {

	// OVERALL PARSING INPUT RULES

	public Rule Input() {
		return Sequence(
				Spacing(), 
				ExpressionList(), 
				Spacing(), 
				EOI);
	}

	public Rule ExpressionInput() {
		return FirstOf(
				Sequence(
					Spacing(), 
					Expression(), 
					Spacing(), 
					FirstOf(EOI,push(error("Extra input after expression")))),
				push(error("Expression expected")));
	}

	public Rule SymbolInput() {
		return Sequence(
				Spacing(), 
				Symbol(), 
				Spacing(), 
				EOI);
	}
	
	/**
	 * Non-empty whitespace object
	 */
	public Rule WhiteSpaceComponent() {
		return FirstOf(WhiteSpaceCharacter(), 
				Sequence("#_", 
						Spacing(), 
						Expression(), 
						drop()), // remember to	drop from value stack!											
				Sequence(';', ZeroOrMore(NoneOf("\n")), '\n'));
	}
	
	/**
	 * Consumes optional whitespace
	 * 
	 * Leaves nothing on stack.
	 */
	public Rule Spacing() {
		return ZeroOrMore(WhiteSpaceComponent());
	}
	
	/**
	 * Matches any non-empty amount of whitespace
	 * 
	 * Leaves nothing on stack.
	 */
	public Rule WhiteSpace() {
		return OneOrMore(WhiteSpaceComponent());
	}

	public Rule WhiteSpaceCharacter() {
		return AnyOf(" \t\f,\r\n");
	}

	// EXPRESSIONS

	/**
	 * Matches a single expression without whitespace
	 * 
	 * Returns the expression value at top of stack.
	 */
	public Rule Expression() {
		return MaybeMeta(FirstOf(
				DelimitedExpression(), 
				UndelimitedExpression()));
	}

	public Rule MaybeMeta(Rule r) {
		return FirstOf(r, 
				Sequence(Meta(), 
						WhiteSpace(), 
						r,
						push((wrapSyntax) ? assocMeta((Syntax) pop(), (Syntax) pop()) : pop())));
	}

	public Syntax assocMeta(Syntax exp, Syntax meta) {
		AHashMap<ACell, ACell> metaMap = interpretMetadata(meta);
		return exp.mergeMeta(metaMap);
	}

	/**
	 * Converts a metadata object according to the following rule: - Map ->
	 * unchanged - Keyword -> {:keyword true} - Any other expression -> {:tag
	 * expression}
	 * 
	 * @param metaNode Syntax node containing metadata
	 * @return Metadata map
	 */
	@SuppressWarnings("unchecked")
	public AHashMap<ACell, ACell> interpretMetadata(Syntax metaNode) {
		ACell val = Syntax.unwrapAll(metaNode);
		if (val instanceof AMap) return (AHashMap<ACell, ACell>) val;
		if (val instanceof Keyword) return Maps.of(val, Boolean.TRUE);
		return Maps.of(Keywords.TAG, val);
	}

	public Rule Meta() {
		return Sequence("^", Expression());
	}

	/**
	 * Matches a delimited expression e.g. {...} or [...]
	 * 
	 * Leaves the value of expression on stack
	 */
	public Rule DelimitedExpression() {
		return FirstOf(DataStructure(), Quoted(DelimitedExpression()));
	}

	/**
	 * Matches an undelimited expression e.g. 10 or :foo
	 * 
	 * Undelimited expressions must be followed by either a delimiter or whitespace
	 * if there are additional expressions following
	 * 
	 * Leaves the value of expression on stack
	 */
	public Rule UndelimitedExpression() {
		return FirstOf(
				Constant(), 
				Symbol(), 
				Keyword(), 
				Quoted(UndelimitedExpression()));
	}

	public class AddAction implements Action<ACell> {
		private Var<ArrayList<ACell>> expVar;

		public AddAction(Var<ArrayList<ACell>> expVar) {
			this.expVar = expVar;
		}

		@Override
		public boolean run(Context<ACell> context) {
			ACell o = pop();
			expVar.get().add(o);
			return true;
		}
	}

	Action<ACell> ListAddAction(Var<ArrayList<ACell>> expVar) {
		return new AddAction(expVar);
	}

	/**
	 * Rule for an expression list, containing zero or more expressions and optional
	 * whitespace
	 *
	 * Returns a List of expressions on stack.
	 */
	public Rule ExpressionList() {
		Var<ArrayList<ACell>> expVar = new Var<>(new ArrayList<>());
		return Sequence(
				Spacing(), 
				ZeroOrMore(Sequence( // initial expressions with following whitespace or delimiter
							Expression(),
							Spacing(),
							ListAddAction(expVar))), 
				Optional(
						Sequence( // final expression without whitespace
								Expression(), ListAddAction(expVar))),
				push(prepare(Lists.create(expVar.get()))));
	}

	// QUOTING and UNQUOTING

	public Rule Quoted(Rule r) {
		return FirstOf(
				Quote(r), 
				SyntaxQuote(r), 
				Unquote(r), 
				UnquoteSplice(r));
	}

	public Rule Quote(Rule r) {
		return Sequence('\'', r, push(prepare(Lists.of(Symbols.QUOTE, pop()))));
	}

	public Rule SyntaxQuote(Rule r) {
		return Sequence('`', r, push(prepare(Lists.of(Symbols.SYNTAX_QUOTE, pop()))));
	}

	public Rule Unquote(Rule r) {
		return Sequence('~', r, push(prepare(Lists.of(Symbols.UNQUOTE, pop()))));
	}

	public Rule UnquoteSplice(Rule r) {
		return Sequence("~@", r, push(prepare(Lists.of(Symbols.UNQUOTE_SPLICING, pop()))));
	}

	// DATA TYPE LITERALS

	@SuppressWarnings("unchecked")
	protected <T extends ACell> ASequence<T> popNodeList() {
		Object o = pop();
		if (o instanceof Syntax) o = ((Syntax) o).getValue();
		return (ASequence<T>) o;
	}

	public Rule DataStructure() {
		return FirstOf(Vector(), List(), Set(), Map());
	}

	public Rule Vector() {
		return Sequence(
				'[', 
				ExpressionList(),
				FirstOf(']', 
						Sequence(FirstOf(AnyOf("})"), EOI), 
								 push(error("Expected closing ']'")))),
				push(prepare(Vectors.create(popNodeList()))));
	}

	public Rule List() {
		return Sequence(
				'(', 
				ExpressionList(),
				FirstOf(')', 
						Sequence(FirstOf(AnyOf("]}"), EOI), 
								 push(error("Expected closing ')'")))),
				push(prepare(Lists.create(popNodeList()))));
	}

	public Rule Set() {
		return Sequence(
				"#{", 
				ExpressionList(),
				FirstOf('}', 
						Sequence(FirstOf(AnyOf("])"), EOI), 
								 push(error("Expected closing '}'")))),
				push(prepare(Sets.create(popNodeList()))));
	}

	public Syntax error(java.lang.String message) {
		throw new ParseException(message);
	}

	public Rule Map() {
		return Sequence(
				"{", 
				ExpressionList(),
				FirstOf('}', 
				Sequence(
						FirstOf(AnyOf("])"), EOI), 
						push(error("Expected closing '}'")))),
				push(prepare(Maps.of(popNodeList().toArray()))));
	}

	// CONSTANT LITERALS

	public Rule Constant() {
		return FirstOf(
				HexLiteral(),
				NumberLiteral(), 
				StringLiteral(), 
				NilLiteral(), 
				BooleanLiteral(), 
				CharLiteral(),
				AddressLiteral()
				);
	}

	public Rule NilLiteral() {
		return Sequence("nil", TestNot(FollowingSymbolCharacter()), push(prepare(null)));
	}

	public Rule CharLiteral() {
		return Sequence('\\',
				FirstOf(Sequence("newline", push(prepareChar('\n'))), 
						Sequence("space", push(prepareChar(' '))), 
						Sequence("tab", push(prepareChar('\t'))),
						Sequence("formfeed", push(prepareChar('\f'))), 
						Sequence("backspace", push(prepareChar('\b'))),
						Sequence("return", push(prepareChar('\r'))),
						Sequence("u", NTimes(4, HexDigit()), push(prepareChar((char) Long.parseLong(match(), 16)))),
						Sequence(ANY, push(prepareChar(match().charAt(0))))));
	}
	
	/**
	 * Prepare AST result Syntax Object
	 * 
	 * @param a Raw parsed object
	 * @return AST object, may be a Syntax Object
	 */
	protected ACell prepareChar(char c) {
		CVMChar cc=CVMChar.create(c);
		if (wrapSyntax) {
			return Syntax.create(cc);
		}
		return cc;
	}

	public Rule BooleanLiteral() {
		return FirstOf(
				Sequence("true", TestNot(FollowingSymbolCharacter()), push(prepare(CVMBool.TRUE))),
				Sequence("false", TestNot(FollowingSymbolCharacter()), push(prepare(CVMBool.FALSE))));
	}

	public Rule StringLiteral() {
		StringVar sb = new StringVar("");

		return Sequence(
				'"', 
				ZeroOrMore(Sequence(StringCharacter(), sb.append(matchOrDefault("0")))),
				push(prepare(Strings.create(Utils.unescapeString(sb.get())))), '"');
	}

	public Rule StringCharacter() {
		return FirstOf(NoneOf("\\\""), EscapeSequence());
	}

	public Rule EscapeSequence() {
		return Sequence('\\', AnyOf("\\\""));
	}

	// SYMBOLS and KEYWORDS
	// Results are stored in a Constant node

	protected Symbol popSymbol() {
		Object o = pop();
		if (o instanceof Syntax) o = ((Syntax) o).getValue();
		return (Symbol) o;
	}

	public Rule Symbol() {
		return FirstOf(QualifiedSymbol(), UnqualifiedSymbol());
	}

	public Rule Keyword() {
		return Sequence(Sequence(':', Symbol()), push(prepare(Keyword.createChecked(popSymbol().getName()))));
	}

	/**
	 * Rule for parsing a qualified symbol with a namespace
	 */
	public Rule QualifiedSymbol() {
		return Sequence(UnqualifiedSymbol(), '/', UnqualifiedSymbol(),
				push(prepare(Symbol.createWithNamespace(popSymbol().getName(), popSymbol().getName()))));
	}

	/**
	 * Rule for parsing an unqualified symbol, uses "null" as the namespace
	 */
	public Rule UnqualifiedSymbol() {
		return Sequence(FirstOf(
				Sequence(InitialSymbolCharacter(), ZeroOrMore(FollowingSymbolCharacter())),
				Sequence(AnyOf(".+-"), NonNumericSymbolCharacter(), ZeroOrMore(FollowingSymbolCharacter())), 
				'/', // allowed on its own as a symbol
				'.' // dot special form
		), push(prepare(matchSymbol())));
	}
	
	public Symbol matchSymbol() {
		Symbol sym=Symbol.create(match());
		if (sym==null) error("Invalid Symbol name");
		return sym;
	}

	public Rule InitialSymbolCharacter() {
		return FirstOf(Alphabet(), AnyOf(".*+!-_?$%&=<>"));
	}

	public Rule FollowingSymbolCharacter() {
		return FirstOf(AlphaNumeric(), AnyOf(".*+!-_?$%&=<>:#"));
	}

	public Rule NonNumericSymbolCharacter() {
		return FirstOf(Alphabet(), AnyOf(".*+!-_?$%&=<>:#"));
	}

	// CHARACTERS

	public Rule AlphaNumeric() {
		return FirstOf(Alphabet(), Digit());
	}

	public Rule Alphabet() {
		return FirstOf(CharRange('a', 'z'), CharRange('A', 'Z'));
	}

	public Rule Digit() {
		return CharRange('0', '9');
	}


	// NUMBERS

	public Rule NumberLiteral() {
		return FirstOf(Double(), Long());
	}

	public Rule Digits() {
		return OneOrMore(Digit());
	}
	
	public Rule SignedInteger() {
		return Sequence(Optional(AnyOf("+-")), Digits());
	}
	
	// HEX
	
	public Rule HexDigit() {
		return FirstOf(CharRange('0', '9'), CharRange('a', 'f'), CharRange('A', 'F'));
	}
	
	public Rule HexBytes() {
		return ZeroOrMore(Sequence(HexDigit(),HexDigit()));
	}
	
	public Rule HexLiteral() {
		return Sequence("0x",Sequence(HexBytes(),TestNot(HexDigit())),push(prepare(Blob.fromHex(match()))));
	}
	
	public Rule AddressLiteral() {
		return Sequence("#", Digits(), push(prepare(Address.create(Long.parseLong(match())))));
	}


	public Rule Long() {
		return Sequence(SignedInteger(), push(prepare(CVMLong.parse(match()))));
	}

	public Rule Double() {
		return Sequence(Sequence(Optional(AnyOf("+-")), Optional(Digits()), '.', Digits(), Optional(ExponentPart())),
				push(prepare(CVMDouble.parse(match()))));
	}

	public Rule ExponentPart() {
		return Sequence(AnyOf("eE"), SignedInteger());
	}

	/**
	 * Prepare AST result Syntax Object
	 * 
	 * @param a Raw parsed object
	 * @return AST object, may be a Syntax Object
	 */
	protected ACell prepare(ACell a) {
		if (wrapSyntax) {
			IndexRange ir = matchRange();
			long start = ir.start;
			//long end = ir.end;
			AHashMap<ACell, ACell> props = Maps.of(Keywords.START, RT.cvm(start));
			//AHashMap<Object, Object> props = Maps.of(Keywords.START, start, Keywords.END, end, Keywords.SOURCE,
			//		tempSource.substring((int)start, (int)end));
			return Syntax.create(a,props);
		}
		return a;
	}

	// MAIN PARSING FUNCTIONALITY

	protected String tempSource;
	public final boolean wrapSyntax;

	/**
	 * Constructor for reader class. Called by Parboiled.createParser
	 * 
	 * @param wrapSyntax Sets flag to determine if Reader should wrap forms in
	 *                   Syntax Objects
	 */
	public Reader(Boolean wrapSyntax) {
		this.wrapSyntax = wrapSyntax;
	}

	// Use a ThreadLocal reader because instances are not thread safe
	private static final ThreadLocal<Reader> formReader = new ThreadLocal<Reader>() {
		@Override
		protected Reader initialValue() {
			return Parboiled.createParser(Reader.class, false);
		}
	};

	// Use a ThreadLocal reader because instances are not thread safe
	private static final ThreadLocal<Reader> syntaxReader = new ThreadLocal<Reader>() {
		@Override
		protected Reader initialValue() {
			return Parboiled.createParser(Reader.class, true);
		}
	};

	private static <T> void checkErrors(ParsingResult<T> result) {
		if (result.hasErrors()) {
			java.util.List<ParseError> errors = result.parseErrors;
			StringBuilder sb = new StringBuilder();
			for (ParseError error : errors) {
				InputBuffer ib = error.getInputBuffer();
				int start = error.getStartIndex();
				int end = error.getEndIndex();
				sb.append("Parse error at " + ib.getPosition(error.getStartIndex()) 
					+ "\n Source: <" + ib.extract(start, end)
					+ ">\n Message: " + error.getErrorMessage()); 
				// + " Input Buffer Line 1: [" + ib.extractLine(1) + "]");
//				sb.append("Parse error at "+ib.getPosition(0)+": "+ib.extract(start, end)+" ERR: "+ error.getErrorMessage());
//				sb.append(result.parseTreeRoot);
			}
			throw new ParseException(sb.toString());
		}
	}

	public static <T extends ACell> T doParse(Rule rule, String source) {
		ParseRunner<T> runner = new ReportingParseRunner<T>(rule);
		return doParse(runner, source);
	}

	protected static <T extends ACell> T doParse(ParseRunner<T> runner, String source) {
		try {
			ParsingResult<T> result = runner.run(source);
			checkErrors(result);
			return result.resultValue;
		} catch (ParserRuntimeException e) {
			// re-wrap parse exceptions
			throw new ParseException(e.getMessage(), e);
		}
	}

	/**
	 * Parses an expression and returns a form
	 * 
	 * @param source
	 * @return Parsed form
	 */
	@SuppressWarnings("unchecked")
	public static <R extends ACell> R read(String source) {
		Reader reader = formReader.get();
		reader.tempSource = source;
		return (R) doParse(new ReportingParseRunner<ACell>(reader.ExpressionInput()), source);
	}

	/**
	 * Parses an expression and returns a Syntax object
	 * 
	 * @param source
	 * @return Parsed form
	 */
	public static Syntax readSyntax(String source) {
		Reader reader = syntaxReader.get();
		reader.tempSource = source;
		return (Syntax) doParse(new ReportingParseRunner<ACell>(reader.ExpressionInput()), source);
	}

	public static ACell readResource(String path) throws IOException {
		String source = Utils.readResourceAsString(path);
		Reader reader = syntaxReader.get(); 
		reader.tempSource = source;
		return doParse(new ReportingParseRunner<ACell>(reader.ExpressionInput()), source);
	}

	/**
	 * Parses an expression list and returns a list of syntax objects
	 * 
	 * @param source
	 * @return List of forms
	 */
	public static AList<Syntax> readAllSyntax(String source) {
		Reader reader = syntaxReader.get();
		reader.tempSource = source;
		Syntax s = (Syntax) doParse(new ReportingParseRunner<ACell>(reader.Input()), source);
		return s.getValue();
	}

	/**
	 * Parses an expression list and returns a list of raw forms
	 * 
	 * @param source
	 * @return List of Syntax Objects
	 */
	@SuppressWarnings("unchecked")
	public static AList<ACell> readAll(String source) {
		Reader reader = formReader.get();
		reader.tempSource = source;
		AList<ACell> list = (AList<ACell>) doParse(new ReportingParseRunner<ACell>(reader.Input()), source);
		return list;
	}

	/**
	 * Parses a symbol
	 * 
	 * @param source
	 * @return Parsed form
	 */
	public static Symbol readSymbol(String source) {
		Reader reader = formReader.get();
		reader.tempSource = source;
		return doParse(new ReportingParseRunner<Symbol>(reader.SymbolInput()), source);
	}

	/**
	 * Parses an expression and returns a form as an Object
	 * 
	 * @param source
	 * @return Parsed form
	 */
	public static Object read(java.io.Reader source) throws IOException {
		char[] arr = new char[8 * 1024];
		StringBuilder buffer = new StringBuilder();
		int numCharsRead;
		while ((numCharsRead = source.read(arr, 0, arr.length)) != -1) {
			buffer.append(arr, 0, numCharsRead);
		}
		return read(buffer.toString());
	}

	public static void main(String[] args) {
		Object result = read("[1 2 foo]");

		System.out.println(result);
	}

}

//@formatter:on