package decaf.frontend.scope;

import decaf.frontend.symbol.ClassSymbol;
import decaf.frontend.symbol.LambdaSymbol;
import decaf.frontend.symbol.MethodSymbol;
import decaf.frontend.symbol.Symbol;
import decaf.frontend.tree.Pos;

import java.util.ListIterator;
import java.util.Objects;
import java.util.Optional;
import java.util.Stack;
import java.util.function.Predicate;

/**
 * A symbol table, which is organized as a stack of scopes, maintained by {@link decaf.frontend.typecheck.Namer}.
 * <p>
 * A typical full scope stack looks like the following:
 * <pre>
 *     LocalScope   --- stack top (current scope)
 *     ...          --- many nested local scopes
 *     LocalScope
 *     FormalScope
 *     ClassScope
 *     ...          --- many parent class scopes
 *     ClassScope
 *     GlobalScope  --- stack bottom
 * </pre>
 * Make sure the global scope is always at the bottom, and NO class scope appears in neither formal nor local scope.
 *
 * @see Scope
 */
public class ScopeStack {

    /**
     * The global scope.
     */
    public final GlobalScope global;

    public ScopeStack(GlobalScope global) {
        this.global = global;
    }

    /**
     * The current scope, at the stack top.
     *
     * @return current scope
     */
    public Scope currentScope() {
        if (scopeStack.isEmpty()) return global;
        return scopeStack.peek();
    }

    /**
     * The innermost (top most on stack) class we now locate in.
     *
     * @return class symbol
     */
    public ClassSymbol currentClass() {
        Objects.requireNonNull(currClass);
        return currClass;
    }

    /**
     * The method we now locate in.
     *
     * @return method symbol
     */
    public MethodSymbol currentMethod() {
        Objects.requireNonNull(currMethod);
        return currMethod;
    }
    
    /**
     * 
     */
    public LambdaSymbol currentLambda() {
    	if (currLambda.empty()) return null;
    	return currLambda.peek();
    }
    
    /**
     * Is the innermost return-needed scope a classScope or a lambdaScope ?
     * @return True for ClassScope, False for LambdaScope; throws exception otherwise
     */
    public boolean retForMethod() {
    	ListIterator<Scope> iter = scopeStack.listIterator(scopeStack.size());
        while (iter.hasPrevious()) {
            var scope = iter.previous();
            if (scope.isClassScope()) return true;
            if (scope.isLambdaScope()) return false;
        }
        assert false;
        return false;
    }
    
    /**
     * @return true if inner is in that
     */
    public boolean IsInnerScope(Scope inner, Scope that) {
    	//System.out.println(inner + " ; " + that);
    	int status = 0;
    	ListIterator<Scope> iter = scopeStack.listIterator(scopeStack.size());
        while (iter.hasPrevious()) {
            var scope = iter.previous();
            //System.out.println(scope);
            if (scope == inner) status = 1;
            if (scope == that && status == 1) status = 2;
        }
        return status == 2;
    }

    /**
     * Open a scope.
     * <p>
     * If the current scope is a class scope, then we must first push all its super classes and then push this.
     * Otherwise, only push the `scope`.
     * <p>
     * REQUIRES: you don't open multiple class scopes, and never open a class scope when the current scope is
     * a formal/local scope.
     */
    public void open(Scope scope) {
        assert !scope.isGlobalScope();
        if (scope.isClassScope()) {
            //assert !currentScope().isFormalOrLocalScope();
            var classScope = (ClassScope) scope;
            classScope.parentScope.ifPresent(this::open);
            currClass = classScope.getOwner();
        }
        else if (scope.isFormalScope()) {
            var formalScope = (FormalScope) scope;
            var tmp = formalScope.getOwner();
            if (tmp != null) // may be formal scope of a lambda expr
            	currMethod = formalScope.getOwner();
        }
        else if (scope.isLambdaScope()) {
        	var lambdaScope = (LambdaScope) scope;
        	currLambda.push(lambdaScope.getOwner());
        }
        scopeStack.push(scope);
    }

    /**
     * Close the current scope.
     * <p>
     * If the current scope is a class scope, then we must close this class and all super classes. Since the global
     * scope is never pushed to the actual {@code scopeStack}, we need to pop all scopes!
     * Otherwise, only pop the current scope.
     */
    public void close() {
        assert !scopeStack.isEmpty();
        Scope scope = scopeStack.pop();
        if (scope.isLambdaScope()) {
        	currLambda.pop();
        }
        if (scope.isClassScope()) {
            while (!scopeStack.isEmpty()) {
                scopeStack.pop();
            }
        }
    }

    /**
     * Lookup a symbol by name. By saying "lookup", the user expects that the symbol is found.
     * In this way, we will always search in all possible scopes and returns the innermost result.
     *
     * @param key symbol's name
     * @return innermost found symbol (if any)
     */
    public Optional<Symbol> lookup(String key) {
        return findWhile(key, whatever -> true, whatever -> true);
    }

    /**
     * Same with {@link #lookup} but we restrict the symbol's position to be before the given {@code pos}.
     *
     * @param key symbol's name
     * @param pos position
     * @return innermost found symbol before {@code pos} (if any)
     */
    public Optional<Symbol> lookupBefore(String key, Pos pos) {
    	//System.out.println("\n");
        return findWhile(key, whatever -> true, s -> !(s.domain().isNestableScope() && s.pos.compareTo(pos) >= 0));
    }

    /**
     * Find if a symbol is conflicting with some already defined symbol. Rules:
     * First, if the current scope is local scope or formal scope, then it cannot conflict with any already defined
     * symbol till the formal scope, and it cannot conflict with any names in the global scope.
     * <p>
     * Second, if the current scope is class scope or global scope, then it cannot conflict with any already defined
     * symbol.
     * <p>
     * NO override checking is issued here -- the type checker is in charge of this!
     *
     * @param key symbol's name
     * @return innermost conflicting symbol (if any)
     */
    public Optional<Symbol> findConflict(String key) {
        if (currentScope().isFormalOrLocalOrLambdaScope())
            return findWhile(key, Scope::isFormalOrLocalOrLambdaScope, whatever -> true).or(() -> global.find(key));
        return lookup(key);
    }

    /**
     * Tell if a class is already defined in the global scope.
     *
     * @param key class's name
     * @return true/false
     */
    public boolean containsClass(String key) {
        return global.containsKey(key);
    }

    /**
     * Lookup a class in the global scope.
     *
     * @param key class's name
     * @return class symbol (if found)
     */
    public Optional<ClassSymbol> lookupClass(String key) {
        return Optional.ofNullable(global.getClass(key));
    }

    /**
     * Get a class from global scope.
     *
     * @param key class's name
     * @return class symbol (if found) or null (if not found)
     */
    public ClassSymbol getClass(String key) {
        return global.getClass(key);
    }

    /**
     * Declare a symbol in the current scope.
     *
     * @param symbol symbol
     * @see Scope#declare
     */
    public void declare(Symbol symbol) {
        currentScope().declare(symbol);
    }

    private Stack<Scope> scopeStack = new Stack<>();
    private ClassSymbol currClass;
    private MethodSymbol currMethod;
    private Stack<LambdaSymbol> currLambda = new Stack<LambdaSymbol>();

    private Optional<Symbol> findWhile(String key, Predicate<Scope> cond, Predicate<Symbol> validator) {
        ListIterator<Scope> iter = scopeStack.listIterator(scopeStack.size());
        //System.out.println("key = " + key);
        while (iter.hasPrevious()) {
            var scope = iter.previous();
            if (!cond.test(scope)) return Optional.empty();
            var symbol = scope.find(key);
            //System.out.println(scope + " | " + symbol);
            if (symbol.isPresent() && validator.test(symbol.get())) {
            	return symbol;
            }
        }
        return cond.test(global) ? global.find(key) : Optional.empty();
    }
    
    public Scope findNestable() {
    	ListIterator<Scope> iter = scopeStack.listIterator(scopeStack.size());
        //System.out.println("key = " + key);
        while (iter.hasPrevious()) {
            var scope = iter.previous();
            if (scope instanceof LocalScope || scope instanceof LambdaScope)
            	return scope;
        }
        assert false;
        return null;
    }
}
