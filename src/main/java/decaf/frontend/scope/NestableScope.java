package decaf.frontend.scope;

import java.util.ArrayList;
import java.util.List;

public abstract class NestableScope extends Scope {
	
	public NestableScope(Kind kind) {
        super(kind);
    }
	
	public List<NestableScope> nestedScopes() {
        return nested;
    }
	
	@Override
	public boolean isNestableScope() {
		return true;
	}
	
	public abstract void addNested(NestableScope t);

    protected List<NestableScope> nested = new ArrayList<>();
}
