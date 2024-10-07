grammar Convex;

/*  =========================================
 *  Grammar for Convex Reader
 *  =========================================
 *  
 *  Refers to tokens defined in the lexer at the bottom of this file
 */ 
 
form
	: quoted
	| pathSymbol
	| primary
	| taggedForm
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

list : LPAREN forms RPAREN;

vector : LVEC forms RVEC;

set : SET_LBR forms RBR;

map : LBR forms RBR;

taggedForm: tag form;

tag: HASH_TAG;

cad3: CAD3;

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
	| cad3
	;
   
longValue: 
   LONG_VALUE;   
   
doubleValue:
   DOUBLE;

symbol
   : SYMBOL;
   
implicitSymbol: INTRINSIC_SYMBOL;
   
specialLiteral: HASH_HASH_SYMBOL;
   
address: ADDRESS;

nil: NIL;

blob: BLOB;

bool: BOOL; 

character: CHARACTER; 

keyword: KEYWORD;

resolve: AT_SYMBOL;

slashSymbol:
  SLASH_SYMBOL;

pathSymbol
   : primary (slashSymbol)+
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

LPAREN: '(';

RPAREN: ')';

LVEC: '[';

RVEC: ']';

SET_LBR: '#{';

LBR: '{';

RBR: '}';

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
  [eE] (DIGITS | SIGNED_DIGITS) SYMBOL_FOLLOWING*;  

ADDRESS:
  '#' [0-9]+;
  
HASH_HASH_SYMBOL:
  '##' NAME;

INTRINSIC_SYMBOL:
  '#%' NAME;
  
HASH_TAG:
  '#' TAG_NAME;
  
CAD3:
  '#[' HEX_BYTE* ']';
  
AT_SYMBOL: 
  '@' NAME;

LONG_VALUE:
  DIGITS | SIGNED_DIGITS;

fragment
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

fragment
DOT: '.';

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

fragment
SLASH: '/';

fragment    
NAME
	: SYMBOL_FIRST SYMBOL_FOLLOWING*;

KEYWORD:
   ':'+ (SLASH | NAME);

SYMBOL
    : SLASH | NAME
    ;
    
SLASH_SYMBOL:
   SLASH (SLASH | NAME);


fragment
TAG_FOLLOWING
  : TAG_FIRST | [0-9] | DOT;

fragment
TAG_FIRST
  : ALPHA;

fragment	
TAG_NAME
	: TAG_FIRST TAG_FOLLOWING*;

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
 *
 * TODO: Should these be skip or channel(HIDDEN)?
 */
 
WS: [ \n\r\t,]+ -> skip;

COMMENT: ';' ~[\r\n]* -> skip;


