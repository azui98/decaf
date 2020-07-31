package decaf.driver.error;

import decaf.frontend.tree.Pos;

/**
 * example锛歩ncompatible argument 3: int given, bool expected<br>
 * 3琛ㄧず鍙戠敓閿欒鐨勬槸绗笁涓弬鏁�<br>
 * PA2
 */
public class BadArgTypeError extends DecafError {

    private int count;

    private String given;

    private String expect;

    public BadArgTypeError(Pos pos, int count, String given,
                           String expect) {
        super(pos);
        this.count = count;
        this.given = given;
        this.expect = expect;
    }

    @Override
    protected String getErrMsg() {
        return "incompatible argument " + count + ": " + given + " given, "
                + expect + " expected";
    }

}
