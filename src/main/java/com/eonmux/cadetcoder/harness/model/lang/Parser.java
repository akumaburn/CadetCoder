package com.eonmux.cadetcoder.harness.model.lang;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads tokens into a model, and refuses one that cannot possibly work.
 *
 * <h2>Why names are checked here</h2>
 *
 * <p>Every name a model uses must already be a parameter, a local, a function it declares, or a
 * builtin. Nothing else exists, so {@code System.exit(1)} is not a security question but a spelling
 * one -- there is no {@code System} to reach. Checking at parse time also keeps a typo from
 * surfacing halfway through a replay, where a crash looks like evidence against the theory rather
 * than a slip in writing it down.</p>
 *
 * <h2>Why arms are numbered here</h2>
 *
 * <p>Coverage is only meaningful if the set of rules a model states is fixed before it runs. Naming
 * each branch as it is read means the enumeration and the marks made at run time cannot drift apart:
 * they are the same identifiers, produced once.</p>
 */
final class Parser {

    /** What an anonymous function is called in a coverage report. */
    private static final String ANONYMOUS = "anon";

    private final List<Token>         tokens;
    private final String[]            sourceLines;
    private final Set<String>         declaredFunctions;
    private final List<Arm>           arms    = new ArrayList<>();
    private final Set<String>         hidden  = new LinkedHashSet<>();
    private final List<FunctionDef>   functions = new ArrayList<>();
    private final Deque<Set<String>>  scopes  = new ArrayDeque<>();

    private int position;
    private int loopDepth;

    Parser(String source, List<Token> tokens) {
        this.tokens            = tokens;
        this.sourceLines       = source.split("\n", -1);
        this.declaredFunctions = declaredFunctionNames(tokens);
    }

    /**
     * Finds every top-level function name before any body is read, so functions may call each other
     * in either order.
     */
    private static Set<String> declaredFunctionNames(List<Token> tokens) {
        Set<String> names = new LinkedHashSet<>();
        int         depth = 0;
        for (int i = 0; i < tokens.size(); i++) {
            TokenType type = tokens.get(i).type();
            if (type == TokenType.LEFT_BRACE) {
                depth++;
            } else if (type == TokenType.RIGHT_BRACE) {
                depth--;
            } else if (depth == 0 && type == TokenType.FN
                       && i + 1 < tokens.size()
                       && tokens.get(i + 1).type() == TokenType.IDENTIFIER) {
                names.add(tokens.get(i + 1).text());
            }
        }
        return names;
    }

    Program parse() {
        while (!check(TokenType.END)) {
            if (match(TokenType.HIDDEN)) {
                hiddenDeclaration();
            } else if (check(TokenType.FN)) {
                functions.add(functionDeclaration());
            } else {
                throw error(peek(), "expected a function or a hidden declaration");
            }
        }
        return new Program(String.join("\n", sourceLines), hidden, functions, arms);
    }

    private void hiddenDeclaration() {
        do {
            hidden.add(consume(TokenType.IDENTIFIER, "expected a field name").text());
        } while (match(TokenType.COMMA));
        consume(TokenType.SEMICOLON, "expected ';' after a hidden declaration");
    }

    private FunctionDef functionDeclaration() {
        Token keyword = consume(TokenType.FN, "expected 'fn'");
        Token name    = consume(TokenType.IDENTIFIER, "expected a function name");
        return function(keyword, name.text());
    }

    private FunctionDef function(Token keyword, String name) {
        String armId = "fn:" + (name == null ? ANONYMOUS : name)
                       + "@" + keyword.line() + ":" + keyword.column();
        arms.add(arm(armId, "fn", keyword));

        consume(TokenType.LEFT_PAREN, "expected '(' after a function name");
        List<String> parameters = new ArrayList<>();
        if (!check(TokenType.RIGHT_PAREN)) {
            do {
                parameters.add(consume(TokenType.IDENTIFIER, "expected a parameter name").text());
            } while (match(TokenType.COMMA));
        }
        consume(TokenType.RIGHT_PAREN, "expected ')' after parameters");

        scopes.push(new LinkedHashSet<>(parameters));
        int enclosingLoops = loopDepth;
        loopDepth = 0;
        List<Stmt> body = block();
        loopDepth = enclosingLoops;
        scopes.pop();
        return new FunctionDef(name, List.copyOf(parameters), body, armId,
                               keyword.line(), keyword.column());
    }

    private List<Stmt> block() {
        consume(TokenType.LEFT_BRACE, "expected '{'");
        scopes.push(new LinkedHashSet<>());
        List<Stmt> statements = new ArrayList<>();
        while (!check(TokenType.RIGHT_BRACE) && !check(TokenType.END)) {
            statements.add(statement());
        }
        scopes.pop();
        consume(TokenType.RIGHT_BRACE, "expected '}'");
        return List.copyOf(statements);
    }

    private Stmt statement() {
        if (check(TokenType.LET)) {
            return letStatement();
        }
        if (check(TokenType.RETURN)) {
            return returnStatement();
        }
        if (check(TokenType.IF)) {
            return ifStatement();
        }
        if (check(TokenType.WHILE)) {
            return whileStatement();
        }
        if (check(TokenType.FOR)) {
            return forStatement();
        }
        if (check(TokenType.BREAK)) {
            Token keyword = insideALoop(advance());
            consume(TokenType.SEMICOLON, "expected ';' after 'break'");
            return new Stmt.Break(keyword.line(), keyword.column());
        }
        if (check(TokenType.CONTINUE)) {
            Token keyword = insideALoop(advance());
            consume(TokenType.SEMICOLON, "expected ';' after 'continue'");
            return new Stmt.Continue(keyword.line(), keyword.column());
        }
        return expressionStatement();
    }

    private Stmt letStatement() {
        Token keyword = advance();
        Token name    = consume(TokenType.IDENTIFIER, "expected a name after 'let'");
        consume(TokenType.EQUAL, "expected '=' after a name");
        Expr value = expression();
        consume(TokenType.SEMICOLON, "expected ';' after a let");
        declare(name.text());
        return new Stmt.Let(name.text(), value, keyword.line(), keyword.column());
    }

    private Stmt returnStatement() {
        Token keyword = advance();
        Expr  value   = check(TokenType.SEMICOLON) ? null : expression();
        consume(TokenType.SEMICOLON, "expected ';' after 'return'");
        return new Stmt.Return(value, keyword.line(), keyword.column());
    }

    private Stmt ifStatement() {
        Token keyword = advance();
        String armId  = "if@" + keyword.line() + ":" + keyword.column();
        arms.add(arm(armId + "/T", "if", keyword));
        arms.add(arm(armId + "/F", "if", keyword));

        consume(TokenType.LEFT_PAREN, "expected '(' after 'if'");
        Expr condition = expression();
        consume(TokenType.RIGHT_PAREN, "expected ')' after a condition");

        List<Stmt> whenTrue  = block();
        List<Stmt> whenFalse = List.of();
        if (match(TokenType.ELSE)) {
            whenFalse = check(TokenType.IF) ? List.of(ifStatement()) : block();
        }
        return new Stmt.If(condition, whenTrue, whenFalse, armId, keyword.line(), keyword.column());
    }

    private Stmt whileStatement() {
        Token  keyword = advance();
        String armId   = "while@" + keyword.line() + ":" + keyword.column();
        arms.add(arm(armId + "/T", "while", keyword));
        arms.add(arm(armId + "/F", "while", keyword));

        consume(TokenType.LEFT_PAREN, "expected '(' after 'while'");
        Expr condition = expression();
        consume(TokenType.RIGHT_PAREN, "expected ')' after a condition");

        loopDepth++;
        List<Stmt> body = block();
        loopDepth--;
        return new Stmt.While(condition, body, armId, keyword.line(), keyword.column());
    }

    private Stmt forStatement() {
        Token  keyword = advance();
        String armId   = "for@" + keyword.line() + ":" + keyword.column();
        arms.add(arm(armId + "/T", "for", keyword));
        arms.add(arm(armId + "/F", "for", keyword));

        consume(TokenType.LEFT_PAREN, "expected '(' after 'for'");
        Token variable = consume(TokenType.IDENTIFIER, "expected a loop variable");
        consume(TokenType.IN, "expected 'in' after a loop variable");
        Expr iterable = expression();
        consume(TokenType.RIGHT_PAREN, "expected ')' after the sequence");

        scopes.push(new LinkedHashSet<>(List.of(variable.text())));
        loopDepth++;
        List<Stmt> body = block();
        loopDepth--;
        scopes.pop();
        return new Stmt.ForIn(variable.text(), iterable, body, armId,
                              keyword.line(), keyword.column());
    }

    private Stmt expressionStatement() {
        Expr target = expression();
        if (match(TokenType.EQUAL)) {
            if (target instanceof Expr.Name name && !isLocal(name.name())) {
                throw new ModelSyntaxException("'" + name.name() + "' is not a local; only a "
                                               + "parameter or a let can be assigned to",
                                               name.line(), name.column());
            }
            if (!(target instanceof Expr.Name || target instanceof Expr.Field
                  || target instanceof Expr.Index)) {
                throw new ModelSyntaxException("this cannot be assigned to",
                                               target.line(), target.column());
            }
            Expr value = expression();
            consume(TokenType.SEMICOLON, "expected ';' after an assignment");
            return new Stmt.Assign(target, value, target.line(), target.column());
        }
        consume(TokenType.SEMICOLON, "expected ';' after an expression");
        return new Stmt.ExprStmt(target, target.line(), target.column());
    }

    private Expr expression() {
        return ternary();
    }

    private Expr ternary() {
        Expr condition = or();
        if (!check(TokenType.QUESTION)) {
            return condition;
        }
        advance();
        String armId = "ifexp@" + condition.line() + ":" + condition.column();
        arms.add(arm(armId + "/T", "ifexp", condition.line(), condition.column()));
        arms.add(arm(armId + "/F", "ifexp", condition.line(), condition.column()));

        Expr whenTrue = expression();
        consume(TokenType.COLON, "expected ':' in a conditional expression");
        Expr whenFalse = expression();
        return new Expr.Ternary(condition, whenTrue, whenFalse, armId,
                                condition.line(), condition.column());
    }

    private Expr or() {
        Expr left = and();
        while (match(TokenType.OR_OR)) {
            left = logical("or", left, and());
        }
        return left;
    }

    private Expr and() {
        Expr left = equality();
        while (match(TokenType.AND_AND)) {
            left = logical("and", left, equality());
        }
        return left;
    }

    private Expr logical(String kind, Expr left, Expr right) {
        String armId = kind + "@" + left.line() + ":" + left.column();
        arms.add(arm(armId + "/T", kind, left.line(), left.column()));
        arms.add(arm(armId + "/F", kind, left.line(), left.column()));
        return new Expr.Logical(kind, left, right, armId, left.line(), left.column());
    }

    private Expr equality() {
        Expr left = comparison();
        while (check(TokenType.EQUAL_EQUAL) || check(TokenType.BANG_EQUAL)) {
            Token operator = advance();
            left = new Expr.Binary(operator.text(), left, comparison(), left.line(), left.column());
        }
        return left;
    }

    private Expr comparison() {
        Expr left = additive();
        while (check(TokenType.LESS) || check(TokenType.LESS_EQUAL)
               || check(TokenType.GREATER) || check(TokenType.GREATER_EQUAL)) {
            Token operator = advance();
            left = new Expr.Binary(operator.text(), left, additive(), left.line(), left.column());
        }
        return left;
    }

    private Expr additive() {
        Expr left = multiplicative();
        while (check(TokenType.PLUS) || check(TokenType.MINUS)) {
            Token operator = advance();
            left = new Expr.Binary(operator.text(), left, multiplicative(), left.line(), left.column());
        }
        return left;
    }

    private Expr multiplicative() {
        Expr left = unary();
        while (check(TokenType.STAR) || check(TokenType.SLASH) || check(TokenType.PERCENT)) {
            Token operator = advance();
            left = new Expr.Binary(operator.text(), left, unary(), left.line(), left.column());
        }
        return left;
    }

    private Expr unary() {
        if (check(TokenType.BANG) || check(TokenType.MINUS)) {
            Token operator = advance();
            return new Expr.Unary(operator.text(), unary(), operator.line(), operator.column());
        }
        return postfix();
    }

    private Expr postfix() {
        Expr value = primary();
        while (true) {
            if (match(TokenType.DOT)) {
                Token name = consume(TokenType.IDENTIFIER, "expected a field name after '.'");
                value = new Expr.Field(value, name.text(), value.line(), value.column());
            } else if (match(TokenType.LEFT_BRACKET)) {
                Expr index = expression();
                consume(TokenType.RIGHT_BRACKET, "expected ']'");
                value = new Expr.Index(value, index, value.line(), value.column());
            } else if (match(TokenType.LEFT_PAREN)) {
                value = new Expr.Call(value, arguments(), value.line(), value.column());
            } else {
                return value;
            }
        }
    }

    private List<Expr> arguments() {
        List<Expr> given = new ArrayList<>();
        if (!check(TokenType.RIGHT_PAREN)) {
            do {
                given.add(expression());
            } while (match(TokenType.COMMA));
        }
        consume(TokenType.RIGHT_PAREN, "expected ')' after arguments");
        return List.copyOf(given);
    }

    private Expr primary() {
        Token token = peek();
        switch (token.type()) {
            case NUMBER, STRING, TRUE, FALSE -> {
                advance();
                return new Expr.Literal(token.value(), token.line(), token.column());
            }
            case NULL -> {
                advance();
                return new Expr.Literal(null, token.line(), token.column());
            }
            case IDENTIFIER -> {
                advance();
                requireInScope(token);
                return new Expr.Name(token.text(), token.line(), token.column());
            }
            case LEFT_PAREN -> {
                advance();
                Expr inner = expression();
                consume(TokenType.RIGHT_PAREN, "expected ')'");
                return inner;
            }
            case LEFT_BRACKET -> {
                return listLiteral(advance());
            }
            case LEFT_BRACE -> {
                return objectLiteral(advance());
            }
            case FN -> {
                Token keyword = advance();
                FunctionDef anonymous = function(keyword, null);
                return new Expr.Lambda(anonymous, keyword.line(), keyword.column());
            }
            default -> throw error(token, "expected a value");
        }
    }

    private Expr listLiteral(Token open) {
        List<Expr> elements = new ArrayList<>();
        if (!check(TokenType.RIGHT_BRACKET)) {
            do {
                elements.add(expression());
            } while (match(TokenType.COMMA));
        }
        consume(TokenType.RIGHT_BRACKET, "expected ']'");
        return new Expr.ListLit(List.copyOf(elements), open.line(), open.column());
    }

    private Expr objectLiteral(Token open) {
        List<String> keys   = new ArrayList<>();
        List<Expr>   values = new ArrayList<>();
        if (!check(TokenType.RIGHT_BRACE)) {
            do {
                Token key = peek();
                if (key.type() != TokenType.STRING && key.type() != TokenType.IDENTIFIER) {
                    throw error(key, "expected a field name");
                }
                advance();
                consume(TokenType.COLON, "expected ':' after a field name");
                keys.add(key.text());
                values.add(expression());
            } while (match(TokenType.COMMA));
        }
        consume(TokenType.RIGHT_BRACE, "expected '}'");
        return new Expr.ObjectLit(List.copyOf(keys), List.copyOf(values), open.line(), open.column());
    }

    /** {@code break} and {@code continue} outside a loop would unwind into whatever called it. */
    private Token insideALoop(Token keyword) {
        if (loopDepth == 0) {
            throw new ModelSyntaxException("'" + keyword.text() + "' is only meaningful inside a loop",
                                           keyword.line(), keyword.column());
        }
        return keyword;
    }

    private void declare(String name) {
        if (scopes.isEmpty()) {
            throw new ModelSyntaxException("a let must be inside a function");
        }
        scopes.peek().add(name);
    }

    private boolean isLocal(String name) {
        for (Set<String> scope : scopes) {
            if (scope.contains(name)) {
                return true;
            }
        }
        return false;
    }

    private void requireInScope(Token token) {
        String name = token.text();
        if (declaredFunctions.contains(name) || Builtins.names().contains(name)) {
            return;
        }
        for (Set<String> scope : scopes) {
            if (scope.contains(name)) {
                return;
            }
        }
        throw new ModelSyntaxException("unknown name '" + name + "'; a model can only use its own "
                                       + "parameters, locals, functions and the standard library",
                                       token.line(), token.column());
    }

    private Arm arm(String id, String kind, Token at) {
        return arm(id, kind, at.line(), at.column());
    }

    private Arm arm(String id, String kind, int line, int column) {
        String snippet = line >= 1 && line <= sourceLines.length
                         ? sourceLines[line - 1].trim() : "";
        return new Arm(id, kind, line, column, snippet);
    }

    private Token consume(TokenType type, String message) {
        if (check(type)) {
            return advance();
        }
        throw error(peek(), message);
    }

    private ModelSyntaxException error(Token token, String message) {
        String found = token.type() == TokenType.END ? "end of the model" : "'" + token.text() + "'";
        return new ModelSyntaxException(message + ", found " + found, token.line(), token.column());
    }

    private boolean match(TokenType type) {
        if (!check(type)) {
            return false;
        }
        advance();
        return true;
    }

    private boolean check(TokenType type) {
        return peek().type() == type;
    }

    private Token peek() {
        return tokens.get(position);
    }

    private Token advance() {
        return tokens.get(position++);
    }
}
