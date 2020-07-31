package decaf.frontend.parsing;

import decaf.driver.error.DecafError;
import decaf.driver.error.MsgError;
import decaf.driver.Config;
import decaf.driver.Phase;
import decaf.frontend.tree.Tree;
import decaf.lowlevel.log.IndentPrinter;
import decaf.printing.PrettyTree;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Set;
import java.util.TreeSet;

/**
 * The alternative parser phase.
 */
public class LLParser extends Phase<InputStream, Tree.TopLevel> {

    public LLParser(Config config) {
        super("parser-ll", config);
    }

    @Override
    public Tree.TopLevel transform(InputStream input) {
        var lexer = new decaf.frontend.parsing.DecafLexer<Parser>(new InputStreamReader(input));
        var parser = new Parser();
        lexer.setup(parser, this);
        parser.setup(lexer, this);
        parser.parse();
        return parser.tree;
    }

    @Override
    public void onSucceed(Tree.TopLevel tree) {
        if (config.target.equals(Config.Target.PA1_LL)) {
            var printer = new PrettyTree(new IndentPrinter(config.output));
            printer.pretty(tree);
            printer.flush();
        }
    }

    /**
     * To avoid issuing the same error multiple times.
     *
     * @param error Decaf error
     */
    @Override
    public void issue(DecafError error) {
        if (!errors.isEmpty()) {
            var last = errors.get(errors.size() - 1);
            if (error.toString().equals(last.toString())) { // ignore
                return;
            }
        }

        super.issue(error);
    }

    private class Parser extends decaf.frontend.parsing.LLTable {
        @Override
        boolean parse() {
            var sv = parseSymbol(start, new TreeSet<>());
            if (sv == null) {
                return false;
            }
            return true;
        }

        @Override
        public int tokenOf(int code) {
            return switch (code) {
                case Tokens.VOID -> VOID;
                case Tokens.BOOL -> BOOL;
                case Tokens.INT -> INT;
                case Tokens.STRING -> STRING;
                case Tokens.CLASS -> CLASS;
                case Tokens.NULL -> NULL;
                case Tokens.EXTENDS -> EXTENDS;
                case Tokens.THIS -> THIS;
                case Tokens.WHILE -> WHILE;
                case Tokens.FOR -> FOR;
                case Tokens.IF -> IF;
                case Tokens.ELSE -> ELSE;
                case Tokens.RETURN -> RETURN;
                case Tokens.BREAK -> BREAK;
                case Tokens.NEW -> NEW;
                case Tokens.PRINT -> PRINT;
                case Tokens.READ_INTEGER -> READ_INTEGER;
                case Tokens.READ_LINE -> READ_LINE;
                case Tokens.BOOL_LIT -> BOOL_LIT;
                case Tokens.INT_LIT -> INT_LIT;
                case Tokens.STRING_LIT -> STRING_LIT;
                case Tokens.IDENTIFIER -> IDENTIFIER;
                case Tokens.AND -> AND;
                case Tokens.OR -> OR;
                case Tokens.STATIC -> STATIC;
                case Tokens.INSTANCE_OF -> INSTANCE_OF;
                case Tokens.LESS_EQUAL -> LESS_EQUAL;
                case Tokens.GREATER_EQUAL -> GREATER_EQUAL;
                case Tokens.EQUAL -> EQUAL;
                case Tokens.NOT_EQUAL -> NOT_EQUAL;
                case Tokens.ABSTRACT -> ABSTRACT;
                case Tokens.VAR -> VAR;
                case Tokens.DEDUCE -> DEDUCE;
                case Tokens.FUN -> FUN;
                default -> code; // single-character, use their ASCII code!
            };
        }

        /**
         * Parse function for every non-terminal, with error recovery.
         * NOTE: the current implementation is buggy and may throw {@link NullPointerException}.
         * TODO: find a correct implementation for error recovery!
         * TODO: You are free to change the method body as you wish, but not the interface!
         *
         * @param symbol the non-terminal to be parsed
         * @return the parsed value of {@code symbol} if parsing succeeds, or else {@code null}.
         */
        boolean hasError = false;
        private SemValue parseSymbol(int symbol, Set<Integer> follow) {
        	var result = query(symbol, token); // get production by lookahead symbol
            var beginA = beginSet(symbol);
            var endA = followSet(symbol);
            endA.addAll(follow);
            
            if (!beginA.contains(token)) {
            	hasError = true;
            	yyerror("syntax error");
            	while (true) {
            		if (beginA.contains(token)) {
            			result = query(symbol, token);
            			break;
            		}
            		else if (endA.contains(token))
            			return null;
            		token = nextToken();
            	}
            }
            int actionId = result.getKey(); // get user-defined action
            var right = result.getValue(); // right-hand side of production
            int length = right.size();
            SemValue[] params = new SemValue[length + 1];

            for (int i = 0; i < length; i++) { // parse right-hand side symbols one by one
                int term = right.get(i);
                params[i + 1] = isNonTerminal(term)
                        ? parseSymbol(term, endA) // for non terminals: recursively parse it
                        : matchToken(term) // for terminals: match token
                        ;
            }

            params[0] = new SemValue(); // initialize return value
            if (!hasError) act(actionId, params); // do user-defined action
            return params[0];
        }

        /**
         * Match if the lookahead token is the same as the expected token.
         *
         * @param expected the expected token.
         * @return sem value
         */
        private SemValue matchToken(int expected) {
            SemValue self = semValue;
            if (token != expected) {
                yyerror("syntax error");
                return null;
            }

            token = nextToken();
            return self;
        }
    }
}