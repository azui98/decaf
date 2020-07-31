package decaf.driver.error;

import decaf.frontend.tree.Pos;

public class VoidVarDefError extends DecafError {
	private String name;
	public VoidVarDefError(Pos pos, String name) {
		super(pos);
		this.name = name;
	}

	@Override
	protected String getErrMsg() {
		return "cannot declare identifier '" + name + "' as void type";
	}
	
}
