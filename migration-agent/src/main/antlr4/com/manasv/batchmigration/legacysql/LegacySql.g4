grammar LegacySql;

selectStatement
    : SELECT selectList
      FROM tableRefList
      (WHERE whereClause)?
      (CONNECT BY connectByExpr)?
      (ORDER BY orderList)?
      SEMI? EOF
    ;

selectList
    : selectItem (COMMA selectItem)*
    ;

selectItem
    : expr (AS? IDENT)?
    ;

tableRefList
    : tableRef (COMMA tableRef)*
    ;

tableRef
    : IDENT IDENT?
    ;

whereClause
    : predicate (logicalOp predicate)*
    ;

logicalOp
    : AND | OR
    ;

predicate
    : rownumPredicate
    | expr compOp expr outerJoinMarker?
    ;

rownumPredicate
    : ROWNUM compOp NUMBER
    ;

outerJoinMarker
    : LPAREN PLUS RPAREN
    ;

connectByExpr
    : expr
    ;

orderList
    : orderItem (COMMA orderItem)*
    ;

orderItem
    : expr (ASC | DESC)?
    ;

compOp
    : EQ | NEQ | LT | LE | GT | GE
    ;

expr
    : expr arithOp expr          # arithExpr
    | LPAREN expr RPAREN         # parenExpr
    | functionCall               # funcExpr
    | columnRef                  # columnExpr
    | literal                    # literalExpr
    ;

arithOp
    : PLUS | MINUS | STAR | SLASH | CONCAT
    ;

functionCall
    : DECODE LPAREN expr (COMMA expr)+ RPAREN
    | NVL LPAREN expr COMMA expr RPAREN
    | SYSDATE
    | SUBSTR LPAREN expr COMMA expr (COMMA expr)? RPAREN
    | UPPER LPAREN expr RPAREN
    | LOWER LPAREN expr RPAREN
    | TO_CHAR LPAREN expr (COMMA STRING)? RPAREN
    ;

columnRef
    : IDENT (DOT IDENT)?
    ;

literal
    : NUMBER
    | STRING
    | ROWNUM
    ;

SELECT: [Ss][Ee][Ll][Ee][Cc][Tt];
FROM: [Ff][Rr][Oo][Mm];
WHERE: [Ww][Hh][Ee][Rr][Ee];
CONNECT: [Cc][Oo][Nn][Nn][Ee][Cc][Tt];
BY: [Bb][Yy];
ORDER: [Oo][Rr][Dd][Ee][Rr];
AND: [Aa][Nn][Dd];
OR: [Oo][Rr];
AS: [Aa][Ss];
ASC: [Aa][Ss][Cc];
DESC: [Dd][Ee][Ss][Cc];
ROWNUM: [Rr][Oo][Ww][Nn][Uu][Mm];
DECODE: [Dd][Ee][Cc][Oo][Dd][Ee];
NVL: [Nn][Vv][Ll];
SYSDATE: [Ss][Yy][Ss][Dd][Aa][Tt][Ee];
SUBSTR: [Ss][Uu][Bb][Ss][Tt][Rr];
UPPER: [Uu][Pp][Pp][Ee][Rr];
LOWER: [Ll][Oo][Ww][Ee][Rr];
TO_CHAR: [Tt][Oo]'_'[Cc][Hh][Aa][Rr];

EQ: '=';
NEQ: '<>' | '!=';
LT: '<';
LE: '<=';
GT: '>';
GE: '>=';
PLUS: '+';
MINUS: '-';
STAR: '*';
SLASH: '/';
CONCAT: '||';
LPAREN: '(';
RPAREN: ')';
COMMA: ',';
DOT: '.';
SEMI: ';';

NUMBER: [0-9]+ ('.' [0-9]+)?;
STRING: '\'' (~'\'')* '\'';
IDENT: [a-zA-Z_][a-zA-Z0-9_]*;

WS: [ \t\r\n]+ -> skip;
