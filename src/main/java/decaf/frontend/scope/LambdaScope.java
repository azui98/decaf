package decaf.frontend.scope;

import decaf.frontend.symbol.ClassSymbol;
import decaf.frontend.symbol.LambdaSymbol;
import decaf.frontend.tree.Pos;

public class LambdaScope extends NestableScope {

    public LambdaScope(FormalScope parent, Scope definedIn) {
    	// parent may be formal/local/lambda
        super(Kind.LAMBDA);
        parent.setNested(this);
        if (definedIn instanceof LocalScope)
        	((LocalScope)definedIn).addNested(this);
        else if (definedIn instanceof LambdaScope)
        	((LambdaScope)definedIn).addNested(this);
        //else System.out.println(definedIn);
        this.formal = parent;
    }
    
    public LambdaSymbol getOwner() {
        return owner;
    }
    
    public FormalScope formal;
    public Pos pos;

    public void setOwner(LambdaSymbol owner) {
        this.owner = owner;
    }
    
    public void addNested(NestableScope t) {
    	super.nested.add(t);
    }
    
    @Override
    public boolean isLambdaScope() {
    	return true;
    }
    
    private LambdaSymbol owner;
}