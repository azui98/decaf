package decaf.frontend.symbol;

import decaf.frontend.scope.FormalScope;
import decaf.frontend.scope.LambdaScope;
import decaf.frontend.tree.Pos;
import decaf.frontend.tree.Tree;
import decaf.frontend.type.FunType;
import decaf.frontend.type.Type;

/**
 * Lambda symbol, representing a lambda definition.
 */
public final class LambdaSymbol extends Symbol {
	
	public FunType type;
    public Tree.Expr rhs = null;
    public Tree.LambdaBlock body = null;
    public final FormalScope scope;
    
    public LambdaScope lambdaScope;
    
    /*
     * for low-level implementation of Lambda Expression in PA3
     */
    //public 
    
    public boolean defByExpr() {
    	return rhs != null;
    }
    
    public LambdaSymbol(FunType type, Pos pos, Tree.Expr rhs, FormalScope scope) {
		super("lambda@"+pos, type, pos);
		this.type = type;
		if (type == null) this.type = new FunType(null, null);
		this.rhs = rhs;
		this.scope = scope;
	}
    
    public LambdaSymbol(FunType type, Pos pos, Tree.LambdaBlock body, FormalScope scope) {
		super("lambda@"+pos, type, pos);
		this.type = type;
		if (type == null) this.type = new FunType(null, null);
		this.body = body;
		this.scope = scope;
	}
    
    @Override
    protected String str() {
        return String.format("function lambda@%s : %s", pos, type);
    }
}
