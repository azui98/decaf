package decaf.driver.error;

import decaf.frontend.tree.Pos;

public class IncompatRetType extends DecafError {
	public IncompatRetType(Pos pos) {
		super(pos);
	}
	@Override
    protected String getErrMsg() {
        return "incompatible return types in blocked expression";
    }
}
