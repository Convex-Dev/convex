// Generated from convex/core/lang/reader/antlr/Convex.g4 by ANTLR 4.9.2
package convex.core.lang.reader.antlr;
import org.antlr.v4.runtime.atn.*;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.misc.*;
import org.antlr.v4.runtime.tree.*;
import java.util.List;
import java.util.Iterator;
import java.util.ArrayList;

@SuppressWarnings({"all", "warnings", "unchecked", "unused", "cast"})
public class ConvexParser extends Parser {
	static { RuntimeMetaData.checkVersion("4.9.2", RuntimeMetaData.VERSION); }

	protected static final DFA[] _decisionToDFA;
	protected static final PredictionContextCache _sharedContextCache =
		new PredictionContextCache();
	public static final int
		T__0=1, T__1=2, T__2=3, T__3=4, T__4=5, T__5=6, SYMBOL_PATH=7, COMMENTED=8, 
		HASH=9, META=10, NIL=11, BOOL=12, DOUBLE=13, DIGITS=14, SIGNED_DIGITS=15, 
		BLOB=16, STRING=17, QUOTING=18, KEYWORD=19, SYMBOL=20, CHARACTER=21, TRASH=22;
	public static final int
		RULE_form = 0, RULE_singleForm = 1, RULE_forms = 2, RULE_dataStructure = 3, 
		RULE_list = 4, RULE_vector = 5, RULE_set = 6, RULE_map = 7, RULE_literal = 8, 
		RULE_longValue = 9, RULE_doubleValue = 10, RULE_specialLiteral = 11, RULE_address = 12, 
		RULE_nil = 13, RULE_blob = 14, RULE_bool = 15, RULE_character = 16, RULE_keyword = 17, 
		RULE_symbol = 18, RULE_pathSymbol = 19, RULE_syntax = 20, RULE_quoted = 21, 
		RULE_string = 22, RULE_commented = 23;
	private static String[] makeRuleNames() {
		return new String[] {
			"form", "singleForm", "forms", "dataStructure", "list", "vector", "set", 
			"map", "literal", "longValue", "doubleValue", "specialLiteral", "address", 
			"nil", "blob", "bool", "character", "keyword", "symbol", "pathSymbol", 
			"syntax", "quoted", "string", "commented"
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

	@Override
	public String getGrammarFileName() { return "Convex.g4"; }

	@Override
	public String[] getRuleNames() { return ruleNames; }

	@Override
	public String getSerializedATN() { return _serializedATN; }

	@Override
	public ATN getATN() { return _ATN; }

	public ConvexParser(TokenStream input) {
		super(input);
		_interp = new ParserATNSimulator(this,_ATN,_decisionToDFA,_sharedContextCache);
	}

	public static class FormContext extends ParserRuleContext {
		public LiteralContext literal() {
			return getRuleContext(LiteralContext.class,0);
		}
		public SymbolContext symbol() {
			return getRuleContext(SymbolContext.class,0);
		}
		public PathSymbolContext pathSymbol() {
			return getRuleContext(PathSymbolContext.class,0);
		}
		public DataStructureContext dataStructure() {
			return getRuleContext(DataStructureContext.class,0);
		}
		public SyntaxContext syntax() {
			return getRuleContext(SyntaxContext.class,0);
		}
		public QuotedContext quoted() {
			return getRuleContext(QuotedContext.class,0);
		}
		public FormContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_form; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).enterForm(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).exitForm(this);
		}
	}

	public final FormContext form() throws RecognitionException {
		FormContext _localctx = new FormContext(_ctx, getState());
		enterRule(_localctx, 0, RULE_form);
		try {
			setState(54);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,0,_ctx) ) {
			case 1:
				enterOuterAlt(_localctx, 1);
				{
				setState(48);
				literal();
				}
				break;
			case 2:
				enterOuterAlt(_localctx, 2);
				{
				setState(49);
				symbol();
				}
				break;
			case 3:
				enterOuterAlt(_localctx, 3);
				{
				setState(50);
				pathSymbol();
				}
				break;
			case 4:
				enterOuterAlt(_localctx, 4);
				{
				setState(51);
				dataStructure();
				}
				break;
			case 5:
				enterOuterAlt(_localctx, 5);
				{
				setState(52);
				syntax();
				}
				break;
			case 6:
				enterOuterAlt(_localctx, 6);
				{
				setState(53);
				quoted();
				}
				break;
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class SingleFormContext extends ParserRuleContext {
		public FormContext form() {
			return getRuleContext(FormContext.class,0);
		}
		public TerminalNode EOF() { return getToken(ConvexParser.EOF, 0); }
		public SingleFormContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_singleForm; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).enterSingleForm(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).exitSingleForm(this);
		}
	}

	public final SingleFormContext singleForm() throws RecognitionException {
		SingleFormContext _localctx = new SingleFormContext(_ctx, getState());
		enterRule(_localctx, 2, RULE_singleForm);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(56);
			form();
			setState(57);
			match(EOF);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class FormsContext extends ParserRuleContext {
		public List<FormContext> form() {
			return getRuleContexts(FormContext.class);
		}
		public FormContext form(int i) {
			return getRuleContext(FormContext.class,i);
		}
		public List<CommentedContext> commented() {
			return getRuleContexts(CommentedContext.class);
		}
		public CommentedContext commented(int i) {
			return getRuleContext(CommentedContext.class,i);
		}
		public FormsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_forms; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).enterForms(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).exitForms(this);
		}
	}

	public final FormsContext forms() throws RecognitionException {
		FormsContext _localctx = new FormsContext(_ctx, getState());
		enterRule(_localctx, 4, RULE_forms);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(63);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while ((((_la) & ~0x3f) == 0 && ((1L << _la) & ((1L << T__0) | (1L << T__2) | (1L << T__4) | (1L << SYMBOL_PATH) | (1L << COMMENTED) | (1L << HASH) | (1L << META) | (1L << NIL) | (1L << BOOL) | (1L << DOUBLE) | (1L << DIGITS) | (1L << SIGNED_DIGITS) | (1L << BLOB) | (1L << STRING) | (1L << QUOTING) | (1L << KEYWORD) | (1L << SYMBOL) | (1L << CHARACTER))) != 0)) {
				{
				setState(61);
				_errHandler.sync(this);
				switch (_input.LA(1)) {
				case T__0:
				case T__2:
				case T__4:
				case SYMBOL_PATH:
				case HASH:
				case META:
				case NIL:
				case BOOL:
				case DOUBLE:
				case DIGITS:
				case SIGNED_DIGITS:
				case BLOB:
				case STRING:
				case QUOTING:
				case KEYWORD:
				case SYMBOL:
				case CHARACTER:
					{
					setState(59);
					form();
					}
					break;
				case COMMENTED:
					{
					setState(60);
					commented();
					}
					break;
				default:
					throw new NoViableAltException(this);
				}
				}
				setState(65);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class DataStructureContext extends ParserRuleContext {
		public ListContext list() {
			return getRuleContext(ListContext.class,0);
		}
		public VectorContext vector() {
			return getRuleContext(VectorContext.class,0);
		}
		public SetContext set() {
			return getRuleContext(SetContext.class,0);
		}
		public MapContext map() {
			return getRuleContext(MapContext.class,0);
		}
		public DataStructureContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_dataStructure; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).enterDataStructure(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).exitDataStructure(this);
		}
	}

	public final DataStructureContext dataStructure() throws RecognitionException {
		DataStructureContext _localctx = new DataStructureContext(_ctx, getState());
		enterRule(_localctx, 6, RULE_dataStructure);
		try {
			setState(70);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case T__0:
				enterOuterAlt(_localctx, 1);
				{
				setState(66);
				list();
				}
				break;
			case T__2:
				enterOuterAlt(_localctx, 2);
				{
				setState(67);
				vector();
				}
				break;
			case HASH:
				enterOuterAlt(_localctx, 3);
				{
				setState(68);
				set();
				}
				break;
			case T__4:
				enterOuterAlt(_localctx, 4);
				{
				setState(69);
				map();
				}
				break;
			default:
				throw new NoViableAltException(this);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class ListContext extends ParserRuleContext {
		public FormsContext forms() {
			return getRuleContext(FormsContext.class,0);
		}
		public ListContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_list; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).enterList(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).exitList(this);
		}
	}

	public final ListContext list() throws RecognitionException {
		ListContext _localctx = new ListContext(_ctx, getState());
		enterRule(_localctx, 8, RULE_list);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(72);
			match(T__0);
			setState(73);
			forms();
			setState(74);
			match(T__1);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class VectorContext extends ParserRuleContext {
		public FormsContext forms() {
			return getRuleContext(FormsContext.class,0);
		}
		public VectorContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_vector; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).enterVector(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).exitVector(this);
		}
	}

	public final VectorContext vector() throws RecognitionException {
		VectorContext _localctx = new VectorContext(_ctx, getState());
		enterRule(_localctx, 10, RULE_vector);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(76);
			match(T__2);
			setState(77);
			forms();
			setState(78);
			match(T__3);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class SetContext extends ParserRuleContext {
		public TerminalNode HASH() { return getToken(ConvexParser.HASH, 0); }
		public FormsContext forms() {
			return getRuleContext(FormsContext.class,0);
		}
		public SetContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_set; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).enterSet(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).exitSet(this);
		}
	}

	public final SetContext set() throws RecognitionException {
		SetContext _localctx = new SetContext(_ctx, getState());
		enterRule(_localctx, 12, RULE_set);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(80);
			match(HASH);
			setState(81);
			match(T__4);
			setState(82);
			forms();
			setState(83);
			match(T__5);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class MapContext extends ParserRuleContext {
		public FormsContext forms() {
			return getRuleContext(FormsContext.class,0);
		}
		public MapContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_map; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).enterMap(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).exitMap(this);
		}
	}

	public final MapContext map() throws RecognitionException {
		MapContext _localctx = new MapContext(_ctx, getState());
		enterRule(_localctx, 14, RULE_map);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(85);
			match(T__4);
			setState(86);
			forms();
			setState(87);
			match(T__5);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class LiteralContext extends ParserRuleContext {
		public NilContext nil() {
			return getRuleContext(NilContext.class,0);
		}
		public BoolContext bool() {
			return getRuleContext(BoolContext.class,0);
		}
		public BlobContext blob() {
			return getRuleContext(BlobContext.class,0);
		}
		public CharacterContext character() {
			return getRuleContext(CharacterContext.class,0);
		}
		public KeywordContext keyword() {
			return getRuleContext(KeywordContext.class,0);
		}
		public SymbolContext symbol() {
			return getRuleContext(SymbolContext.class,0);
		}
		public AddressContext address() {
			return getRuleContext(AddressContext.class,0);
		}
		public StringContext string() {
			return getRuleContext(StringContext.class,0);
		}
		public LongValueContext longValue() {
			return getRuleContext(LongValueContext.class,0);
		}
		public DoubleValueContext doubleValue() {
			return getRuleContext(DoubleValueContext.class,0);
		}
		public SpecialLiteralContext specialLiteral() {
			return getRuleContext(SpecialLiteralContext.class,0);
		}
		public LiteralContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_literal; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).enterLiteral(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).exitLiteral(this);
		}
	}

	public final LiteralContext literal() throws RecognitionException {
		LiteralContext _localctx = new LiteralContext(_ctx, getState());
		enterRule(_localctx, 16, RULE_literal);
		try {
			setState(100);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,4,_ctx) ) {
			case 1:
				enterOuterAlt(_localctx, 1);
				{
				setState(89);
				nil();
				}
				break;
			case 2:
				enterOuterAlt(_localctx, 2);
				{
				setState(90);
				bool();
				}
				break;
			case 3:
				enterOuterAlt(_localctx, 3);
				{
				setState(91);
				blob();
				}
				break;
			case 4:
				enterOuterAlt(_localctx, 4);
				{
				setState(92);
				character();
				}
				break;
			case 5:
				enterOuterAlt(_localctx, 5);
				{
				setState(93);
				keyword();
				}
				break;
			case 6:
				enterOuterAlt(_localctx, 6);
				{
				setState(94);
				symbol();
				}
				break;
			case 7:
				enterOuterAlt(_localctx, 7);
				{
				setState(95);
				address();
				}
				break;
			case 8:
				enterOuterAlt(_localctx, 8);
				{
				setState(96);
				string();
				}
				break;
			case 9:
				enterOuterAlt(_localctx, 9);
				{
				setState(97);
				longValue();
				}
				break;
			case 10:
				enterOuterAlt(_localctx, 10);
				{
				setState(98);
				doubleValue();
				}
				break;
			case 11:
				enterOuterAlt(_localctx, 11);
				{
				setState(99);
				specialLiteral();
				}
				break;
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class LongValueContext extends ParserRuleContext {
		public TerminalNode DIGITS() { return getToken(ConvexParser.DIGITS, 0); }
		public TerminalNode SIGNED_DIGITS() { return getToken(ConvexParser.SIGNED_DIGITS, 0); }
		public LongValueContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_longValue; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).enterLongValue(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).exitLongValue(this);
		}
	}

	public final LongValueContext longValue() throws RecognitionException {
		LongValueContext _localctx = new LongValueContext(_ctx, getState());
		enterRule(_localctx, 18, RULE_longValue);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(102);
			_la = _input.LA(1);
			if ( !(_la==DIGITS || _la==SIGNED_DIGITS) ) {
			_errHandler.recoverInline(this);
			}
			else {
				if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
				_errHandler.reportMatch(this);
				consume();
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class DoubleValueContext extends ParserRuleContext {
		public TerminalNode DOUBLE() { return getToken(ConvexParser.DOUBLE, 0); }
		public DoubleValueContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_doubleValue; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).enterDoubleValue(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).exitDoubleValue(this);
		}
	}

	public final DoubleValueContext doubleValue() throws RecognitionException {
		DoubleValueContext _localctx = new DoubleValueContext(_ctx, getState());
		enterRule(_localctx, 20, RULE_doubleValue);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(104);
			match(DOUBLE);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class SpecialLiteralContext extends ParserRuleContext {
		public List<TerminalNode> HASH() { return getTokens(ConvexParser.HASH); }
		public TerminalNode HASH(int i) {
			return getToken(ConvexParser.HASH, i);
		}
		public SymbolContext symbol() {
			return getRuleContext(SymbolContext.class,0);
		}
		public SpecialLiteralContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_specialLiteral; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).enterSpecialLiteral(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).exitSpecialLiteral(this);
		}
	}

	public final SpecialLiteralContext specialLiteral() throws RecognitionException {
		SpecialLiteralContext _localctx = new SpecialLiteralContext(_ctx, getState());
		enterRule(_localctx, 22, RULE_specialLiteral);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(106);
			match(HASH);
			setState(107);
			match(HASH);
			setState(108);
			symbol();
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class AddressContext extends ParserRuleContext {
		public TerminalNode HASH() { return getToken(ConvexParser.HASH, 0); }
		public TerminalNode DIGITS() { return getToken(ConvexParser.DIGITS, 0); }
		public AddressContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_address; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).enterAddress(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).exitAddress(this);
		}
	}

	public final AddressContext address() throws RecognitionException {
		AddressContext _localctx = new AddressContext(_ctx, getState());
		enterRule(_localctx, 24, RULE_address);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(110);
			match(HASH);
			setState(111);
			match(DIGITS);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class NilContext extends ParserRuleContext {
		public TerminalNode NIL() { return getToken(ConvexParser.NIL, 0); }
		public NilContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_nil; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).enterNil(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).exitNil(this);
		}
	}

	public final NilContext nil() throws RecognitionException {
		NilContext _localctx = new NilContext(_ctx, getState());
		enterRule(_localctx, 26, RULE_nil);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(113);
			match(NIL);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class BlobContext extends ParserRuleContext {
		public TerminalNode BLOB() { return getToken(ConvexParser.BLOB, 0); }
		public BlobContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_blob; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).enterBlob(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).exitBlob(this);
		}
	}

	public final BlobContext blob() throws RecognitionException {
		BlobContext _localctx = new BlobContext(_ctx, getState());
		enterRule(_localctx, 28, RULE_blob);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(115);
			match(BLOB);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class BoolContext extends ParserRuleContext {
		public TerminalNode BOOL() { return getToken(ConvexParser.BOOL, 0); }
		public BoolContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_bool; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).enterBool(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).exitBool(this);
		}
	}

	public final BoolContext bool() throws RecognitionException {
		BoolContext _localctx = new BoolContext(_ctx, getState());
		enterRule(_localctx, 30, RULE_bool);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(117);
			match(BOOL);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class CharacterContext extends ParserRuleContext {
		public TerminalNode CHARACTER() { return getToken(ConvexParser.CHARACTER, 0); }
		public CharacterContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_character; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).enterCharacter(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).exitCharacter(this);
		}
	}

	public final CharacterContext character() throws RecognitionException {
		CharacterContext _localctx = new CharacterContext(_ctx, getState());
		enterRule(_localctx, 32, RULE_character);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(119);
			match(CHARACTER);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class KeywordContext extends ParserRuleContext {
		public TerminalNode KEYWORD() { return getToken(ConvexParser.KEYWORD, 0); }
		public KeywordContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_keyword; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).enterKeyword(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).exitKeyword(this);
		}
	}

	public final KeywordContext keyword() throws RecognitionException {
		KeywordContext _localctx = new KeywordContext(_ctx, getState());
		enterRule(_localctx, 34, RULE_keyword);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(121);
			match(KEYWORD);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class SymbolContext extends ParserRuleContext {
		public TerminalNode SYMBOL() { return getToken(ConvexParser.SYMBOL, 0); }
		public SymbolContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_symbol; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).enterSymbol(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).exitSymbol(this);
		}
	}

	public final SymbolContext symbol() throws RecognitionException {
		SymbolContext _localctx = new SymbolContext(_ctx, getState());
		enterRule(_localctx, 36, RULE_symbol);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(123);
			match(SYMBOL);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class PathSymbolContext extends ParserRuleContext {
		public TerminalNode SYMBOL_PATH() { return getToken(ConvexParser.SYMBOL_PATH, 0); }
		public PathSymbolContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_pathSymbol; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).enterPathSymbol(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).exitPathSymbol(this);
		}
	}

	public final PathSymbolContext pathSymbol() throws RecognitionException {
		PathSymbolContext _localctx = new PathSymbolContext(_ctx, getState());
		enterRule(_localctx, 38, RULE_pathSymbol);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(125);
			match(SYMBOL_PATH);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class SyntaxContext extends ParserRuleContext {
		public TerminalNode META() { return getToken(ConvexParser.META, 0); }
		public List<FormContext> form() {
			return getRuleContexts(FormContext.class);
		}
		public FormContext form(int i) {
			return getRuleContext(FormContext.class,i);
		}
		public SyntaxContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_syntax; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).enterSyntax(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).exitSyntax(this);
		}
	}

	public final SyntaxContext syntax() throws RecognitionException {
		SyntaxContext _localctx = new SyntaxContext(_ctx, getState());
		enterRule(_localctx, 40, RULE_syntax);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(127);
			match(META);
			setState(128);
			form();
			setState(129);
			form();
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class QuotedContext extends ParserRuleContext {
		public TerminalNode QUOTING() { return getToken(ConvexParser.QUOTING, 0); }
		public FormContext form() {
			return getRuleContext(FormContext.class,0);
		}
		public QuotedContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_quoted; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).enterQuoted(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).exitQuoted(this);
		}
	}

	public final QuotedContext quoted() throws RecognitionException {
		QuotedContext _localctx = new QuotedContext(_ctx, getState());
		enterRule(_localctx, 42, RULE_quoted);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(131);
			match(QUOTING);
			setState(132);
			form();
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class StringContext extends ParserRuleContext {
		public TerminalNode STRING() { return getToken(ConvexParser.STRING, 0); }
		public StringContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_string; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).enterString(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).exitString(this);
		}
	}

	public final StringContext string() throws RecognitionException {
		StringContext _localctx = new StringContext(_ctx, getState());
		enterRule(_localctx, 44, RULE_string);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(134);
			match(STRING);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class CommentedContext extends ParserRuleContext {
		public TerminalNode COMMENTED() { return getToken(ConvexParser.COMMENTED, 0); }
		public FormContext form() {
			return getRuleContext(FormContext.class,0);
		}
		public CommentedContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_commented; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).enterCommented(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ConvexListener ) ((ConvexListener)listener).exitCommented(this);
		}
	}

	public final CommentedContext commented() throws RecognitionException {
		CommentedContext _localctx = new CommentedContext(_ctx, getState());
		enterRule(_localctx, 46, RULE_commented);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(136);
			match(COMMENTED);
			setState(137);
			form();
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static final String _serializedATN =
		"\3\u608b\ua72a\u8133\ub9ed\u417c\u3be7\u7786\u5964\3\30\u008e\4\2\t\2"+
		"\4\3\t\3\4\4\t\4\4\5\t\5\4\6\t\6\4\7\t\7\4\b\t\b\4\t\t\t\4\n\t\n\4\13"+
		"\t\13\4\f\t\f\4\r\t\r\4\16\t\16\4\17\t\17\4\20\t\20\4\21\t\21\4\22\t\22"+
		"\4\23\t\23\4\24\t\24\4\25\t\25\4\26\t\26\4\27\t\27\4\30\t\30\4\31\t\31"+
		"\3\2\3\2\3\2\3\2\3\2\3\2\5\29\n\2\3\3\3\3\3\3\3\4\3\4\7\4@\n\4\f\4\16"+
		"\4C\13\4\3\5\3\5\3\5\3\5\5\5I\n\5\3\6\3\6\3\6\3\6\3\7\3\7\3\7\3\7\3\b"+
		"\3\b\3\b\3\b\3\b\3\t\3\t\3\t\3\t\3\n\3\n\3\n\3\n\3\n\3\n\3\n\3\n\3\n\3"+
		"\n\3\n\5\ng\n\n\3\13\3\13\3\f\3\f\3\r\3\r\3\r\3\r\3\16\3\16\3\16\3\17"+
		"\3\17\3\20\3\20\3\21\3\21\3\22\3\22\3\23\3\23\3\24\3\24\3\25\3\25\3\26"+
		"\3\26\3\26\3\26\3\27\3\27\3\27\3\30\3\30\3\31\3\31\3\31\3\31\2\2\32\2"+
		"\4\6\b\n\f\16\20\22\24\26\30\32\34\36 \"$&(*,.\60\2\3\3\2\20\21\2\u0089"+
		"\28\3\2\2\2\4:\3\2\2\2\6A\3\2\2\2\bH\3\2\2\2\nJ\3\2\2\2\fN\3\2\2\2\16"+
		"R\3\2\2\2\20W\3\2\2\2\22f\3\2\2\2\24h\3\2\2\2\26j\3\2\2\2\30l\3\2\2\2"+
		"\32p\3\2\2\2\34s\3\2\2\2\36u\3\2\2\2 w\3\2\2\2\"y\3\2\2\2${\3\2\2\2&}"+
		"\3\2\2\2(\177\3\2\2\2*\u0081\3\2\2\2,\u0085\3\2\2\2.\u0088\3\2\2\2\60"+
		"\u008a\3\2\2\2\629\5\22\n\2\639\5&\24\2\649\5(\25\2\659\5\b\5\2\669\5"+
		"*\26\2\679\5,\27\28\62\3\2\2\28\63\3\2\2\28\64\3\2\2\28\65\3\2\2\28\66"+
		"\3\2\2\28\67\3\2\2\29\3\3\2\2\2:;\5\2\2\2;<\7\2\2\3<\5\3\2\2\2=@\5\2\2"+
		"\2>@\5\60\31\2?=\3\2\2\2?>\3\2\2\2@C\3\2\2\2A?\3\2\2\2AB\3\2\2\2B\7\3"+
		"\2\2\2CA\3\2\2\2DI\5\n\6\2EI\5\f\7\2FI\5\16\b\2GI\5\20\t\2HD\3\2\2\2H"+
		"E\3\2\2\2HF\3\2\2\2HG\3\2\2\2I\t\3\2\2\2JK\7\3\2\2KL\5\6\4\2LM\7\4\2\2"+
		"M\13\3\2\2\2NO\7\5\2\2OP\5\6\4\2PQ\7\6\2\2Q\r\3\2\2\2RS\7\13\2\2ST\7\7"+
		"\2\2TU\5\6\4\2UV\7\b\2\2V\17\3\2\2\2WX\7\7\2\2XY\5\6\4\2YZ\7\b\2\2Z\21"+
		"\3\2\2\2[g\5\34\17\2\\g\5 \21\2]g\5\36\20\2^g\5\"\22\2_g\5$\23\2`g\5&"+
		"\24\2ag\5\32\16\2bg\5.\30\2cg\5\24\13\2dg\5\26\f\2eg\5\30\r\2f[\3\2\2"+
		"\2f\\\3\2\2\2f]\3\2\2\2f^\3\2\2\2f_\3\2\2\2f`\3\2\2\2fa\3\2\2\2fb\3\2"+
		"\2\2fc\3\2\2\2fd\3\2\2\2fe\3\2\2\2g\23\3\2\2\2hi\t\2\2\2i\25\3\2\2\2j"+
		"k\7\17\2\2k\27\3\2\2\2lm\7\13\2\2mn\7\13\2\2no\5&\24\2o\31\3\2\2\2pq\7"+
		"\13\2\2qr\7\20\2\2r\33\3\2\2\2st\7\r\2\2t\35\3\2\2\2uv\7\22\2\2v\37\3"+
		"\2\2\2wx\7\16\2\2x!\3\2\2\2yz\7\27\2\2z#\3\2\2\2{|\7\25\2\2|%\3\2\2\2"+
		"}~\7\26\2\2~\'\3\2\2\2\177\u0080\7\t\2\2\u0080)\3\2\2\2\u0081\u0082\7"+
		"\f\2\2\u0082\u0083\5\2\2\2\u0083\u0084\5\2\2\2\u0084+\3\2\2\2\u0085\u0086"+
		"\7\24\2\2\u0086\u0087\5\2\2\2\u0087-\3\2\2\2\u0088\u0089\7\23\2\2\u0089"+
		"/\3\2\2\2\u008a\u008b\7\n\2\2\u008b\u008c\5\2\2\2\u008c\61\3\2\2\2\78"+
		"?AHf";
	public static final ATN _ATN =
		new ATNDeserializer().deserialize(_serializedATN.toCharArray());
	static {
		_decisionToDFA = new DFA[_ATN.getNumberOfDecisions()];
		for (int i = 0; i < _ATN.getNumberOfDecisions(); i++) {
			_decisionToDFA[i] = new DFA(_ATN.getDecisionState(i), i);
		}
	}
}