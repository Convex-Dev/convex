grammar Convex;

form
	: literal
	| symbol
	| dataStructure
	| syntax
	| quoted
	;
	
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
	| longValue
	;
   
longValue: 
   DIGITS | SIGNED_DIGITS;   
   
address: HASH DIGITS;

nil: NIL;

blob: BLOB;

bool: BOOL; 

character: CHARACTER; 

keyword: KEYWORD;

symbol: SYMBOL;

syntax: META form form;

quoted: QUOTING form;

/*  =========================================
 *  Lexer stuff below here
 *  =========================================
 */ 

HASH: '#';

META: '^';

NIL: 'nil';

BOOL : 'true' | 'false' ;

// Number. Needs to go before Symbols!

DIGITS:
  [0-9]+;
  
SIGNED_DIGITS:
  '-' DIGITS | '+' DIGITS;
  
             
BLOB: '0x' HEX_DIGIT*;

fragment           
HEX_BYTE: HEX_DIGIT HEX_DIGIT;

fragment 
HEX_DIGIT: [0-9a-fA-F];

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
    | [0-9]
    | '.' | '*' | '+' | '!' | '-' | '?' | '$' | '%' | '&' | '=' | '<' | '>'
    ;

fragment
SYMBOL_FOLLOWING
    : SYMBOL_FIRST
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

