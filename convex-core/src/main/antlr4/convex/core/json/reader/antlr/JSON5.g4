/** Adapted from: https://github.com/antlr/grammars-v4/blob/master/json/JSON.g4 */

// See also : https://github.com/antlr/grammars-v4/blob/master/json5/JSON5.g4

// $antlr-format alignTrailingComments true, columnLimit 150, minEmptyLines 1, maxEmptyLinesToKeep 1, reflowComments false, useTab false
// $antlr-format allowShortRulesOnASingleLine false, allowShortBlocksOnASingleLine true, alignSemicolons hanging, alignColons hanging

grammar JSON5;

json
    : value EOF
    ;

obj
    : '{' pair (',' pair)* ','? '}'
    | '{' '}'
    ;

pair
    : key ':' value
    ;
    
key
	: string 
	| identifier
	;
	
identifier
	: IDENTIFIER
	;
	
// Note single training comma allowed after values
array
    : '[' value (',' value)* ','? ']'
    | '[' ']'
    ;

value
    : string
    | number
    | obj
    | array
    | bool
    | nil
    ;

bool
	: 'true'
    | 'false';

string
	: STRING;

// numbers allow extra IEEE754 values as per JSON5	
number
	: NUMBER | NUMERIC_LITERAL;
	
nil
	: 'null';

STRING
	: '"' DOUBLE_QUOTE_CHAR* '"'
	| '\'' SINGLE_QUOTE_CHAR* '\''
	;

fragment DOUBLE_QUOTE_CHAR
    : ~["\\\r\n]
    | ESC
    ;

fragment SINGLE_QUOTE_CHAR
    : ~['\\\r\n]
    | ESC
    ;

fragment ESC
    : '\\' (['"\\/bfnrtv0] | UNICODE)
    ;

fragment UNICODE
    : 'u' HEX HEX HEX HEX
    ;

fragment HEX
    : [0-9a-fA-F]
    ;

fragment SAFECODEPOINT
    : ~["\\]
    ;

NUMBER
    : SYMBOL? INT ('.' [0-9]*)? EXP?
    | SYMBOL? '.' [0-9]+ EXP?
    | SYMBOL? '0' [xX] HEX+
    ;
    
NUMERIC_LITERAL
    : SYMBOL? 'Infinity'
    | 'NaN'
    ;
    
IDENTIFIER
    : IDENTIFIER_START IDENTIFIER_PART*
    ;

fragment IDENTIFIER_START
    : [\p{L}]
    | '$'
    | '_'
    | '\\' UNICODE
    ;

fragment IDENTIFIER_PART
    : IDENTIFIER_START
    | [\p{M}]
    | [\p{N}]
    | [\p{Pc}]
    | '\u200C'
    | '\u200D'
    ;
    
fragment SYMBOL
    : '+'
    | '-'
    ;
    
fragment NEWLINE
    : '\r\n'
    | [\r\n\u2028\u2029]
    ;

fragment INT
    // integer part forbids leading 0s (e.g. `01`)
    : '0'
    | [1-9] [0-9]*
    ;

fragment EXP
    // exponent number permits leading 0s (e.g. `1e01`)
    : [Ee] [+-]? [0-9]*
    ;

// Multi-line comments (ignored)
MULTILINE_COMMENT
	: '/*' .*? '*/' -> skip
	;

// Single-line comments (ignored)
SINGLELINE_COMMENT
	: '//' .*? (NEWLINE | EOF) -> skip
	;

WS
    : [ \t\n\r\u00A0\uFEFF\u2003]+ -> skip
    ;
    