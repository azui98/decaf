package decaf.driver.error;

import decaf.frontend.tree.Pos;

/**
 * example cannot access field 'homework' from 'Others'<br>
 * example cannot access field 'homework' from 'int[]'<br>
 * PA2
 */
public class NotClassFieldError extends DecafError {

    private String name;

    private String owner;

    public NotClassFieldError(Pos pos, String name, String owner) {
        super(pos);
        this.name = name;
        this.owner = owner;
    }

    @Override
    protected String getErrMsg() {
        return "cannot access field '" + name + "' from '" + owner + "'";
    }

}
