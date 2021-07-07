grammar Convex;

form
	: literal
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
	| number
	;
	
number
   : longValue;
   
longValue: LONG;   

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
    | DIGIT
    | '.' | '*' | '+' | '!' | '-' | '?' | '$' | '%' | '&' | '=' | '<' | '>'
    ;

fragment
SYMBOL_FOLLOWING
    : SYMBOL_FIRST
    | ':' | '#'
    ;
    
fragment
ALPHA: [a-z] | [A-Z];

fragment
LONG:
  SIGN? DIGIT+;
  
SIGN:
  '-' | '+';
    
fragment 
DIGIT : [0-9];
    

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

