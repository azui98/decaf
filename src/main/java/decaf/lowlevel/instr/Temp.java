package decaf.lowlevel.instr;

import decaf.lowlevel.tac.FuncVisitor;

/**
 * A pseudo register, or a temporary variable.
 * <p>
 * For short, we simply call it temp.
 */
public class Temp implements Comparable<Temp> {
    /**
     * Index, must be unique inside a function.
     */
    public final int index;
    
    private FuncVisitor definedIn = null;
    public void setDomain(FuncVisitor dom) { definedIn = dom; }
    public FuncVisitor getDomain() { return definedIn; }
    
    public Temp(int index) {
        this.index = index;
    }

    @Override
    public String toString() {
        return "_T" + index;
    }

    @Override
    public int compareTo(Temp that) {
        return this.index - that.index;
    }
    
    @Override
    public int hashCode() { return index; }
    
    @Override
    public boolean equals(Object that) {
    	if (getClass() != that.getClass()) return false;
    	return index == ((Temp)that).index;
    }
}