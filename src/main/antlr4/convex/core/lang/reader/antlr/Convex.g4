grammar Convex;

form
	: literal
	| dataStructure
	;
	
forms: form* ;

dataStructure:
	list | vector | set;

list : '(' forms ')';

vector : '[' forms ']';

set : '#{' forms '}';

literal 
	: nil
	| bool
	| longValue
	;
   
longValue: 
   LONG;   

nil: NIL;

bool: BOOL; 

character: CHARACTER; 

keyword: ':' symbol;

symbol: SYMBOL;

/*  =========================================
 *  Lexer stuff below here
 *  =========================================
 */ 

NIL: 'nil';

BOOL : 'true' | 'false' ;

// Number. Needs to go before Symbols!


LONG: SIGN? DIGITS;

fragment
DIGITS:
  [0-9]+;

fragment 
SIGN:
  '-' | '+';


// Symbols

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

