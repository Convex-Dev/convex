grammar Convex;

form
	: literal
	| symbol
	| dataStructure
	| syntax
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
	| keyword
	| symbol
	| address
	| longValue
	;
   
longValue: 
   DIGITS | SIGNED_DIGITS;   
   
address: HASH DIGITS;

nil: NIL;

bool: BOOL; 

character: CHARACTER; 

keyword: KEYWORD;

symbol: SYMBOL;

syntax: META form form;

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

