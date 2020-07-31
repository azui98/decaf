package decaf.driver.error;

import decaf.frontend.tree.Pos;

public class LambdaArgCountError extends DecafError {
	private int given, needed;
	
	public LambdaArgCountError(Pos pos, int needed, int given) {
        super(pos);
        this.given = given;
        this.needed = needed;
    }
	
	@Override
    protected String getErrMsg() {
        return "lambda expression expects " + needed + " argument(s) but " + given + " given";
    }
}
