package decaf.driver.error;

import decaf.frontend.tree.Pos;

public class VoidArgError extends DecafError {
	public VoidArgError(Pos pos) {
		super(pos);
	}

	@Override
	protected String getErrMsg() {
		return "arguments in function type must be non-void known type";
	}
	
}