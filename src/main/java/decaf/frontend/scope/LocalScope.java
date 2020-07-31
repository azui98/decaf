package decaf.frontend.scope;

import java.util.ArrayList;
import java.util.List;

import decaf.frontend.scope.Scope.Kind;

/**
 * Local scope: stores locally-defined variables.
 */
public class LocalScope extends NestableScope {

    public LocalScope(Scope parent) {
    	// parent may be formal/local/lambda
        super(Kind.LOCAL);
        if (parent.isFormalScope()) {
            ((FormalScope) parent).setNested(this);
        } else {
            ((NestableScope) parent).nested.add(this);
        }
    }

    @Override
    public boolean isLocalScope() {
        return true;
    }
    
    public void addNested(NestableScope t) {
    	super.nested.add(t);
    }

    /**
     * Collect all local/lambda scopes defined inside this scope.
     *
     * @return local/lambda scopes
     */
}
