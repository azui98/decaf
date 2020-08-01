package decaf.driver.error;


import decaf.frontend.tree.Pos;

public class LockParamNotInteger extends DecafError {
    private final String typename;

    public LockParamNotInteger(Pos pos, String typename) {
        super(pos);
        this.typename = typename;
    }

    @Override
    protected String getErrMsg() {
        return "Lock ID must be an integer, but type " + typename + " given";
    }
}
