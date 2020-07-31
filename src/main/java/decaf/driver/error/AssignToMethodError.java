package decaf.driver.error;

import decaf.frontend.tree.Pos;

public class AssignToMethodError extends DecafError {
	
	String name;
	public AssignToMethodError(Pos pos, String name) {
		super(pos);
		this.name = name;
	}
	
	@Override
    protected String getErrMsg() {
        return "cannot assign value to class member method '" + name + "'";
    }
	
}
