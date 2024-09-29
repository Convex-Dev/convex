grammar Convex;
 
form
	: quoted
	| pathSymbol
	| primary
	;
	
primary
	: dataStructure
	| syntax
	| resolve
	| atom
	;

singleForm: form EOF;
	
forms: (form | commented) *;

allForms: forms EOF;

dataStructure:
	list | vector | set | map;

list : '(' forms ')';

vector : '[' forms ']';

set : '#{' forms '}';

map : '{' forms '}';

atom
  : symbol 
  | literal
  | implicitSymbol
  ;
  
literal 
	: nil
	| bool
	| blob
	| character
	| keyword
	| symbol
	| address
	| string
	| longValue
	| doubleValue
	| specialLiteral
	;
   
longValue: 
   DIGITS | SIGNED_DIGITS;   
   
doubleValue:
   DOUBLE;

// Note slash is a special case that is a Symbol on its own
symbol
   : SLASH 
   | SYMBOL;
   
implicitSymbol: INTRINSIC_SYMBOL;
   
specialLiteral: HASH_HASH_SYMBOL;
   
address: ADDRESS;

nil: NIL;

blob: BLOB;

bool: BOOL; 

character: CHARACTER; 

keyword: KEYWORD;

resolve: AT_SYMBOL;

pathSymbol
   : primary ('/' symbol)+
   ;

syntax: META form form;

quoted: QUOTING form;

string: STRING;

commented: COMMENTED form;

/*  =========================================
 *  Lexer stuff below here
 *  =========================================
 */ 
 
COMMENTED: '#_';



META: '^';

SLASH: '/';

NIL: 'nil';

BOOL : 'true' | 'false' ;

// Number. Needs to go before Symbols!

DOUBLE:
  (DIGITS | SIGNED_DIGITS) DOUBLE_TAIL;
  
fragment  
DOUBLE_TAIL:
  DECIMAL EPART | DECIMAL | EPART;

fragment  
DECIMAL:
  '.' DIGITS;
  
fragment 
EPART:
  [eE] (DIGITS | SIGNED_DIGITS) SYMBOL_FOLLOWING*;  

ADDRESS:
  '#' [0-9]+;
  
HASH_HASH_SYMBOL:
  '##' NAME;
  
INTRINSIC_SYMBOL:
  '#%' NAME;
  
AT_SYMBOL: 
  '@' NAME;

DIGITS:
  [0-9]+;
  
SIGNED_DIGITS:
  '-' DIGITS;
  
BLOB: '0x' HEX_DIGIT*;

fragment           
HEX_BYTE: HEX_DIGIT HEX_DIGIT;

fragment 
HEX_DIGIT: [0-9a-fA-F];

STRING: '"' STRING_CHAR* '"' ;
	
fragment
STRING_CHAR: ~["\\] | STRING_ESCAPE;

fragment
STRING_ESCAPE: '\\' ([btnfr"'\\] | OCTAL_BYTE | UNICODE_ESCAPE);

fragment
OCTAL_BYTE
	:	OCTAL_DIGIT
	|	OCTAL_DIGIT OCTAL_DIGIT
	|	[0-3] OCTAL_DIGIT OCTAL_DIGIT
	;
	
fragment
UNICODE_ESCAPE: 'u' HEX_BYTE HEX_BYTE;

fragment
OCTAL_DIGIT : [0-7];

// Quoting

QUOTING: '\'' | '`' | '~' | '~@';

// Symbols and Keywords


KEYWORD:
   ':'+ (SLASH | NAME);

SYMBOL
    : NAME
    ;
    
fragment    
NAME
	: SYMBOL_FIRST SYMBOL_FOLLOWING*;

CHARACTER
  : '\\u' HEX_BYTE HEX_BYTE
  | '\\' .
  | SPECIAL_CHARACTER;

fragment  
SPECIAL_CHARACTER
    : '\\' ( 'newline'
           | 'return'
           | 'space'
           | 'tab'
           | 'formfeed'
           | 'backspace' ) ;


// Test case "a*+!-_?<>=!" should be a symbol

fragment
SYMBOL_FIRST
    : ALPHA
    | '.' | '*' | '+' | '!' | '-' | '_' | '?' | '$' | '%' | '&' | '=' | '<' | '>'
    ;

fragment
SYMBOL_FOLLOWING
    : SYMBOL_FIRST
    | [0-9]
    | ':' | '#'
    ;
    
fragment
ALPHA: [a-z] | [A-Z];

/*
 * Whitespace and comments
 */
 
WS: [ \n\r\t,]+ -> channel(HIDDEN);

COMMENT: ';' ~[\r\n]* -> channel(HIDDEN);


