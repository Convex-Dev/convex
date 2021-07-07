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
	
forms: form* ;

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

pathSymbol: (address | symbol) '/' symbol;

syntax: META form form;

quoted: QUOTING form;

string: STRING;

commented: COMMENTED form;

/*  =========================================
 *  Lexer stuff below here
 *  =========================================
 */ 

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
  
fragment  
SIGNED_DIGITS:
  '-' DIGITS;
  
BLOB: '0x' HEX_DIGIT*;

fragment           
HEX_BYTE: HEX_DIGIT HEX_DIGIT;

fragment 
HEX_DIGIT: [0-9a-fA-F];

STRING : '"' ( ~'"' | '\\' '"' )* '"' ;

// Quoting

QUOTING: '\'' | '`' | '~' | '~@';

// Symbols and Keywords

KEYWORD:
   ':' NAME;

SYMBOL
    : '/'
    | NAME
    ;
    
    fragment
    
NAME: SYMBOL_FIRST SYMBOL_FOLLOWING*;

CHARACTER
  : '\\' .
  |SPECIAL_CHARACTER;

fragment  
SPECIAL_CHARACTER
    : '\\' ( 'newline'
           | 'return'
           | 'space'
           | 'tab'
           | 'formfeed'
           | 'backspace' ) ;


fragment
SYMBOL_FIRST
    : ALPHA
    | '.' | '*' | '+' | '!' | '-' | '?' | '$' | '%' | '&' | '=' | '<' | '>'
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
    : ( WS | COMMENT ) -> channel(HIDDEN)
    ;

