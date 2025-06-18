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
	: NUMBER | 'NaN' | 'Infinity' | '+Infinity' | '-Infinity';
	
nil
	: 'null';

STRING
    : '"' (ESC | SAFECODEPOINT)* '"'
    ;

fragment ESC
    : '\\' (["\\/bfnrt] | UNICODE)
    ;

fragment UNICODE
    : 'u' HEX HEX HEX HEX
    ;

fragment HEX
    : [0-9a-fA-F]
    ;

fragment SAFECODEPOINT
    : ~ ["\\\u0000-\u001F]
    ;

NUMBER
    : '-'? INT ('.' [0-9]+)? EXP?
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
	: '//' ~[\r\n]* -> skip
	;

WS
    : [ \t\n\r\u00A0\uFEFF\u2003]+ -> skip
    ;
    