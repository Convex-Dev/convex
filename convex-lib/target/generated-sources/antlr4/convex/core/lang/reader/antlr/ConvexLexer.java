// Generated from convex/core/lang/reader/antlr/Convex.g4 by ANTLR 4.9.2
package convex.core.lang.reader.antlr;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.atn.*;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.misc.*;

@SuppressWarnings({"all", "warnings", "unchecked", "unused", "cast"})
public class ConvexLexer extends Lexer {
	static { RuntimeMetaData.checkVersion("4.9.2", RuntimeMetaData.VERSION); }

	protected static final DFA[] _decisionToDFA;
	protected static final PredictionContextCache _sharedContextCache =
		new PredictionContextCache();
	public static final int
		T__0=1, T__1=2, T__2=3, T__3=4, T__4=5, T__5=6, SYMBOL_PATH=7, COMMENTED=8, 
		HASH=9, META=10, NIL=11, BOOL=12, DOUBLE=13, DIGITS=14, SIGNED_DIGITS=15, 
		BLOB=16, STRING=17, QUOTING=18, KEYWORD=19, SYMBOL=20, CHARACTER=21, TRASH=22;
	public static String[] channelNames = {
		"DEFAULT_TOKEN_CHANNEL", "HIDDEN"
	};

	public static String[] modeNames = {
		"DEFAULT_MODE"
	};

	private static String[] makeRuleNames() {
		return new String[] {
			"T__0", "T__1", "T__2", "T__3", "T__4", "T__5", "SYMBOL_PATH", "COMMENTED", 
			"HASH", "META", "NIL", "BOOL", "DOUBLE", "DOUBLE_TAIL", "DECIMAL", "EPART", 
			"DIGITS", "SIGNED_DIGITS", "BLOB", "HEX_BYTE", "HEX_DIGIT", "STRING", 
			"QUOTING", "KEYWORD", "SYMBOL", "NAME", "CHARACTER", "SPECIAL_CHARACTER", 
			"SYMBOL_FIRST", "SYMBOL_FOLLOWING", "ALPHA", "WS", "COMMENT", "TRASH"
		};
	}
	public static final String[] ruleNames = makeRuleNames();

	private static String[] makeLiteralNames() {
		return new String[] {
			null, "'('", "')'", "'['", "']'", "'{'", "'}'", null, "'#_'", "'#'", 
			"'^'", "'nil'"
		};
	}
	private static final String[] _LITERAL_NAMES = makeLiteralNames();
	private static String[] makeSymbolicNames() {
		return new String[] {
			null, null, null, null, null, null, null, "SYMBOL_PATH", "COMMENTED", 
			"HASH", "META", "NIL", "BOOL", "DOUBLE", "DIGITS", "SIGNED_DIGITS", "BLOB", 
			"STRING", "QUOTING", "KEYWORD", "SYMBOL", "CHARACTER", "TRASH"
		};
	}
	private static final String[] _SYMBOLIC_NAMES = makeSymbolicNames();
	public static final Vocabulary VOCABULARY = new VocabularyImpl(_LITERAL_NAMES, _SYMBOLIC_NAMES);

	/**
	 * @deprecated Use {@link #VOCABULARY} instead.
	 */
	@Deprecated
	public static final String[] tokenNames;
	static {
		tokenNames = new String[_SYMBOLIC_NAMES.length];
		for (int i = 0; i < tokenNames.length; i++) {
			tokenNames[i] = VOCABULARY.getLiteralName(i);
			if (tokenNames[i] == null) {
				tokenNames[i] = VOCABULARY.getSymbolicName(i);
			}

			if (tokenNames[i] == null) {
				tokenNames[i] = "<INVALID>";
			}
		}
	}

	@Override
	@Deprecated
	public String[] getTokenNames() {
		return tokenNames;
	}

	@Override

	public Vocabulary getVocabulary() {
		return VOCABULARY;
	}


	public ConvexLexer(CharStream input) {
		super(input);
		_interp = new LexerATNSimulator(this,_ATN,_decisionToDFA,_sharedContextCache);
	}

	@Override
	public String getGrammarFileName() { return "Convex.g4"; }

	@Override
	public String[] getRuleNames() { return ruleNames; }

	@Override
	public String getSerializedATN() { return _serializedATN; }

	@Override
	public String[] getChannelNames() { return channelNames; }

	@Override
	public String[] getModeNames() { return modeNames; }

	@Override
	public ATN getATN() { return _ATN; }

	public static final String _serializedATN =
		"\3\u608b\ua72a\u8133\ub9ed\u417c\u3be7\u7786\u5964\2\30\u010a\b\1\4\2"+
		"\t\2\4\3\t\3\4\4\t\4\4\5\t\5\4\6\t\6\4\7\t\7\4\b\t\b\4\t\t\t\4\n\t\n\4"+
		"\13\t\13\4\f\t\f\4\r\t\r\4\16\t\16\4\17\t\17\4\20\t\20\4\21\t\21\4\22"+
		"\t\22\4\23\t\23\4\24\t\24\4\25\t\25\4\26\t\26\4\27\t\27\4\30\t\30\4\31"+
		"\t\31\4\32\t\32\4\33\t\33\4\34\t\34\4\35\t\35\4\36\t\36\4\37\t\37\4 \t"+
		" \4!\t!\4\"\t\"\4#\t#\3\2\3\2\3\3\3\3\3\4\3\4\3\5\3\5\3\6\3\6\3\7\3\7"+
		"\3\b\3\b\3\b\3\b\5\bX\n\b\3\b\3\b\3\b\3\t\3\t\3\t\3\n\3\n\3\13\3\13\3"+
		"\f\3\f\3\f\3\f\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\5\rq\n\r\3\16\3\16"+
		"\5\16u\n\16\3\16\3\16\3\17\3\17\3\17\3\17\3\17\5\17~\n\17\3\20\3\20\3"+
		"\20\3\21\3\21\3\21\5\21\u0086\n\21\3\22\6\22\u0089\n\22\r\22\16\22\u008a"+
		"\3\23\3\23\3\23\3\24\3\24\3\24\3\24\7\24\u0094\n\24\f\24\16\24\u0097\13"+
		"\24\3\25\3\25\3\25\3\26\3\26\3\27\3\27\3\27\3\27\7\27\u00a2\n\27\f\27"+
		"\16\27\u00a5\13\27\3\27\3\27\3\30\3\30\3\30\5\30\u00ac\n\30\3\31\3\31"+
		"\3\31\3\32\3\32\3\33\3\33\3\33\7\33\u00b6\n\33\f\33\16\33\u00b9\13\33"+
		"\5\33\u00bb\n\33\3\34\3\34\3\34\3\34\3\34\3\34\3\34\3\34\3\34\5\34\u00c6"+
		"\n\34\3\35\3\35\3\35\3\35\3\35\3\35\3\35\3\35\3\35\3\35\3\35\3\35\3\35"+
		"\3\35\3\35\3\35\3\35\3\35\3\35\3\35\3\35\3\35\3\35\3\35\3\35\3\35\3\35"+
		"\3\35\3\35\3\35\3\35\3\35\3\35\3\35\3\35\3\35\3\35\3\35\3\35\5\35\u00ef"+
		"\n\35\3\36\3\36\5\36\u00f3\n\36\3\37\3\37\5\37\u00f7\n\37\3 \5 \u00fa"+
		"\n \3!\3!\3\"\3\"\7\"\u0100\n\"\f\"\16\"\u0103\13\"\3#\3#\5#\u0107\n#"+
		"\3#\3#\2\2$\3\3\5\4\7\5\t\6\13\7\r\b\17\t\21\n\23\13\25\f\27\r\31\16\33"+
		"\17\35\2\37\2!\2#\20%\21\'\22)\2+\2-\23/\24\61\25\63\26\65\2\67\279\2"+
		";\2=\2?\2A\2C\2E\30\3\2\f\4\2GGgg\3\2\62;\5\2\62;CHch\3\2$$\5\2))bb\u0080"+
		"\u0080\b\2##&(,-/\60>Aaa\4\2%%\62<\4\2C\\c|\6\2\13\f\17\17\"\"..\4\2\f"+
		"\f\17\17\2\u0115\2\3\3\2\2\2\2\5\3\2\2\2\2\7\3\2\2\2\2\t\3\2\2\2\2\13"+
		"\3\2\2\2\2\r\3\2\2\2\2\17\3\2\2\2\2\21\3\2\2\2\2\23\3\2\2\2\2\25\3\2\2"+
		"\2\2\27\3\2\2\2\2\31\3\2\2\2\2\33\3\2\2\2\2#\3\2\2\2\2%\3\2\2\2\2\'\3"+
		"\2\2\2\2-\3\2\2\2\2/\3\2\2\2\2\61\3\2\2\2\2\63\3\2\2\2\2\67\3\2\2\2\2"+
		"E\3\2\2\2\3G\3\2\2\2\5I\3\2\2\2\7K\3\2\2\2\tM\3\2\2\2\13O\3\2\2\2\rQ\3"+
		"\2\2\2\17W\3\2\2\2\21\\\3\2\2\2\23_\3\2\2\2\25a\3\2\2\2\27c\3\2\2\2\31"+
		"p\3\2\2\2\33t\3\2\2\2\35}\3\2\2\2\37\177\3\2\2\2!\u0082\3\2\2\2#\u0088"+
		"\3\2\2\2%\u008c\3\2\2\2\'\u008f\3\2\2\2)\u0098\3\2\2\2+\u009b\3\2\2\2"+
		"-\u009d\3\2\2\2/\u00ab\3\2\2\2\61\u00ad\3\2\2\2\63\u00b0\3\2\2\2\65\u00ba"+
		"\3\2\2\2\67\u00c5\3\2\2\29\u00c7\3\2\2\2;\u00f2\3\2\2\2=\u00f6\3\2\2\2"+
		"?\u00f9\3\2\2\2A\u00fb\3\2\2\2C\u00fd\3\2\2\2E\u0106\3\2\2\2GH\7*\2\2"+
		"H\4\3\2\2\2IJ\7+\2\2J\6\3\2\2\2KL\7]\2\2L\b\3\2\2\2MN\7_\2\2N\n\3\2\2"+
		"\2OP\7}\2\2P\f\3\2\2\2QR\7\177\2\2R\16\3\2\2\2SX\5\65\33\2TU\5\23\n\2"+
		"UV\5#\22\2VX\3\2\2\2WS\3\2\2\2WT\3\2\2\2XY\3\2\2\2YZ\7\61\2\2Z[\5\65\33"+
		"\2[\20\3\2\2\2\\]\7%\2\2]^\7a\2\2^\22\3\2\2\2_`\7%\2\2`\24\3\2\2\2ab\7"+
		"`\2\2b\26\3\2\2\2cd\7p\2\2de\7k\2\2ef\7n\2\2f\30\3\2\2\2gh\7v\2\2hi\7"+
		"t\2\2ij\7w\2\2jq\7g\2\2kl\7h\2\2lm\7c\2\2mn\7n\2\2no\7u\2\2oq\7g\2\2p"+
		"g\3\2\2\2pk\3\2\2\2q\32\3\2\2\2ru\5#\22\2su\5%\23\2tr\3\2\2\2ts\3\2\2"+
		"\2uv\3\2\2\2vw\5\35\17\2w\34\3\2\2\2xy\5\37\20\2yz\5!\21\2z~\3\2\2\2{"+
		"~\5\37\20\2|~\5!\21\2}x\3\2\2\2}{\3\2\2\2}|\3\2\2\2~\36\3\2\2\2\177\u0080"+
		"\7\60\2\2\u0080\u0081\5#\22\2\u0081 \3\2\2\2\u0082\u0085\t\2\2\2\u0083"+
		"\u0086\5#\22\2\u0084\u0086\5%\23\2\u0085\u0083\3\2\2\2\u0085\u0084\3\2"+
		"\2\2\u0086\"\3\2\2\2\u0087\u0089\t\3\2\2\u0088\u0087\3\2\2\2\u0089\u008a"+
		"\3\2\2\2\u008a\u0088\3\2\2\2\u008a\u008b\3\2\2\2\u008b$\3\2\2\2\u008c"+
		"\u008d\7/\2\2\u008d\u008e\5#\22\2\u008e&\3\2\2\2\u008f\u0090\7\62\2\2"+
		"\u0090\u0091\7z\2\2\u0091\u0095\3\2\2\2\u0092\u0094\5+\26\2\u0093\u0092"+
		"\3\2\2\2\u0094\u0097\3\2\2\2\u0095\u0093\3\2\2\2\u0095\u0096\3\2\2\2\u0096"+
		"(\3\2\2\2\u0097\u0095\3\2\2\2\u0098\u0099\5+\26\2\u0099\u009a\5+\26\2"+
		"\u009a*\3\2\2\2\u009b\u009c\t\4\2\2\u009c,\3\2\2\2\u009d\u00a3\7$\2\2"+
		"\u009e\u00a2\n\5\2\2\u009f\u00a0\7^\2\2\u00a0\u00a2\7$\2\2\u00a1\u009e"+
		"\3\2\2\2\u00a1\u009f\3\2\2\2\u00a2\u00a5\3\2\2\2\u00a3\u00a1\3\2\2\2\u00a3"+
		"\u00a4\3\2\2\2\u00a4\u00a6\3\2\2\2\u00a5\u00a3\3\2\2\2\u00a6\u00a7\7$"+
		"\2\2\u00a7.\3\2\2\2\u00a8\u00ac\t\6\2\2\u00a9\u00aa\7\u0080\2\2\u00aa"+
		"\u00ac\7B\2\2\u00ab\u00a8\3\2\2\2\u00ab\u00a9\3\2\2\2\u00ac\60\3\2\2\2"+
		"\u00ad\u00ae\7<\2\2\u00ae\u00af\5\65\33\2\u00af\62\3\2\2\2\u00b0\u00b1"+
		"\5\65\33\2\u00b1\64\3\2\2\2\u00b2\u00bb\7\61\2\2\u00b3\u00b7\5;\36\2\u00b4"+
		"\u00b6\5=\37\2\u00b5\u00b4\3\2\2\2\u00b6\u00b9\3\2\2\2\u00b7\u00b5\3\2"+
		"\2\2\u00b7\u00b8\3\2\2\2\u00b8\u00bb\3\2\2\2\u00b9\u00b7\3\2\2\2\u00ba"+
		"\u00b2\3\2\2\2\u00ba\u00b3\3\2\2\2\u00bb\66\3\2\2\2\u00bc\u00bd\7^\2\2"+
		"\u00bd\u00be\7w\2\2\u00be\u00bf\3\2\2\2\u00bf\u00c0\5)\25\2\u00c0\u00c1"+
		"\5)\25\2\u00c1\u00c6\3\2\2\2\u00c2\u00c3\7^\2\2\u00c3\u00c6\13\2\2\2\u00c4"+
		"\u00c6\59\35\2\u00c5\u00bc\3\2\2\2\u00c5\u00c2\3\2\2\2\u00c5\u00c4\3\2"+
		"\2\2\u00c68\3\2\2\2\u00c7\u00ee\7^\2\2\u00c8\u00c9\7p\2\2\u00c9\u00ca"+
		"\7g\2\2\u00ca\u00cb\7y\2\2\u00cb\u00cc\7n\2\2\u00cc\u00cd\7k\2\2\u00cd"+
		"\u00ce\7p\2\2\u00ce\u00ef\7g\2\2\u00cf\u00d0\7t\2\2\u00d0\u00d1\7g\2\2"+
		"\u00d1\u00d2\7v\2\2\u00d2\u00d3\7w\2\2\u00d3\u00d4\7t\2\2\u00d4\u00ef"+
		"\7p\2\2\u00d5\u00d6\7u\2\2\u00d6\u00d7\7r\2\2\u00d7\u00d8\7c\2\2\u00d8"+
		"\u00d9\7e\2\2\u00d9\u00ef\7g\2\2\u00da\u00db\7v\2\2\u00db\u00dc\7c\2\2"+
		"\u00dc\u00ef\7d\2\2\u00dd\u00de\7h\2\2\u00de\u00df\7q\2\2\u00df\u00e0"+
		"\7t\2\2\u00e0\u00e1\7o\2\2\u00e1\u00e2\7h\2\2\u00e2\u00e3\7g\2\2\u00e3"+
		"\u00e4\7g\2\2\u00e4\u00ef\7f\2\2\u00e5\u00e6\7d\2\2\u00e6\u00e7\7c\2\2"+
		"\u00e7\u00e8\7e\2\2\u00e8\u00e9\7m\2\2\u00e9\u00ea\7u\2\2\u00ea\u00eb"+
		"\7r\2\2\u00eb\u00ec\7c\2\2\u00ec\u00ed\7e\2\2\u00ed\u00ef\7g\2\2\u00ee"+
		"\u00c8\3\2\2\2\u00ee\u00cf\3\2\2\2\u00ee\u00d5\3\2\2\2\u00ee\u00da\3\2"+
		"\2\2\u00ee\u00dd\3\2\2\2\u00ee\u00e5\3\2\2\2\u00ef:\3\2\2\2\u00f0\u00f3"+
		"\5? \2\u00f1\u00f3\t\7\2\2\u00f2\u00f0\3\2\2\2\u00f2\u00f1\3\2\2\2\u00f3"+
		"<\3\2\2\2\u00f4\u00f7\5;\36\2\u00f5\u00f7\t\b\2\2\u00f6\u00f4\3\2\2\2"+
		"\u00f6\u00f5\3\2\2\2\u00f7>\3\2\2\2\u00f8\u00fa\t\t\2\2\u00f9\u00f8\3"+
		"\2\2\2\u00fa@\3\2\2\2\u00fb\u00fc\t\n\2\2\u00fcB\3\2\2\2\u00fd\u0101\7"+
		"=\2\2\u00fe\u0100\n\13\2\2\u00ff\u00fe\3\2\2\2\u0100\u0103\3\2\2\2\u0101"+
		"\u00ff\3\2\2\2\u0101\u0102\3\2\2\2\u0102D\3\2\2\2\u0103\u0101\3\2\2\2"+
		"\u0104\u0107\5A!\2\u0105\u0107\5C\"\2\u0106\u0104\3\2\2\2\u0106\u0105"+
		"\3\2\2\2\u0107\u0108\3\2\2\2\u0108\u0109\b#\2\2\u0109F\3\2\2\2\26\2Wp"+
		"t}\u0085\u008a\u0095\u00a1\u00a3\u00ab\u00b7\u00ba\u00c5\u00ee\u00f2\u00f6"+
		"\u00f9\u0101\u0106\3\2\3\2";
	public static final ATN _ATN =
		new ATNDeserializer().deserialize(_serializedATN.toCharArray());
	static {
		_decisionToDFA = new DFA[_ATN.getNumberOfDecisions()];
		for (int i = 0; i < _ATN.getNumberOfDecisions(); i++) {
			_decisionToDFA[i] = new DFA(_ATN.getDecisionState(i), i);
		}
	}
}