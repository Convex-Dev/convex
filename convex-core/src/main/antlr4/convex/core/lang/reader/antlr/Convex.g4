grammar Convex;

form
	: literal
	| symbol
	| pathSymbol
	| dataStructure
	| syntax
	| quoted
	;

singleForm: form EOF;
	
forms: (form | commented) * ;

dataStructure:
	list | vector | set | map;

list : '(' forms ')';

vector : '[' forms ']';

set : HASH '{' forms '}';

map : '{' forms '}';

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
   
specialLiteral: HASH HASH symbol;
   
address: HASH DIGITS;

nil: NIL;

blob: BLOB;

bool: BOOL; 

character: CHARACTER; 

keyword: KEYWORD;

symbol: SYMBOL;

pathSymbol: SYMBOL_PATH;

syntax: META form form;

quoted: QUOTING form;

string: STRING;

commented: COMMENTED form;

/*  =========================================
 *  Lexer stuff below here
 *  =========================================
 */ 
 
SYMBOL_PATH:
	(NAME | HASH DIGITS) ('/' NAME)+;
 
COMMENTED: '#_';

HASH: '#';

META: '^';

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
  [eE] (DIGITS | SIGNED_DIGITS);  

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
STRING_CHAR: ~["\\\r\n] | STRING_ESCAPE;

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
   ':' NAME;

SYMBOL
    : NAME
    ;
    
fragment    
NAME
	: '/'
	| SYMBOL_FIRST SYMBOL_FOLLOWING*;

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
 
fragment
WS : [ \n\r\t,] ;

fragment
COMMENT: ';' ~[\r\n]* ;

TRASH
    : ( WS | COMMENT ) -> skip
    ;

