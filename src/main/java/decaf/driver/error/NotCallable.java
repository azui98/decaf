package decaf.driver.error;

import decaf.frontend.tree.Pos;


public class NotCallable extends DecafError {

	private String name;
    public NotCallable(Pos pos, String t) {
        super(pos);
        name = t;
    }

    @Override
    protected String getErrMsg() {
        return name + " is not a callable type";
    }

}
