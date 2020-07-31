package decaf.driver.error;

import decaf.frontend.tree.Pos;

/**
 * example：field 'money' not found in 'Student'<br>
 * PA2
 */
public class FuncArgCountError extends DecafError {

    private String name;
    int needed, given;

    public FuncArgCountError(Pos pos, String name, int needed, int given) {
        super(pos);
        this.name = name;
        this.needed = needed;
        this.given = given;
    }

    @Override
    protected String getErrMsg() {
        return "function '" + name + "' expects " + needed +
        		" argument(s) but " + given +" given";
    }

}
