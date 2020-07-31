package decaf.driver.error;

import decaf.frontend.tree.Pos;

public class CannotInstanceAbstract extends DecafError {
	
	private String name;
	
	public CannotInstanceAbstract(Pos pos, String name) {
		super(pos);
        this.name = name;
	}
	
	@Override
    protected String getErrMsg() {
        return "cannot instantiate abstract class '" + name + "'";
    }
	
}
