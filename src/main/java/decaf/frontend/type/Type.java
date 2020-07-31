package decaf.frontend.type;

import decaf.frontend.tree.Pos;

/**
 * Type.
 * <p>
 * Decaf has a very simple type system, consisting of:
 * <ol>
 *     <li>basic types: int, bool, string (and void)</li>
 *     <li>array types</li>
 *     <li>class types</li>
 *     <li>function types (cannot be expressed in programs, but we use them to type check function calls)</li>
 * </ol>
 * <p>
 * Types are resolved by {@link decaf.frontend.typecheck.Typer}.
 *
 * @see BuiltInType
 * @see ClassType
 * @see ArrayType
 * @see FunType
 */
public abstract class Type {

    /**
     * Is this type int, bool, or string?
     *
     * @see BuiltInType#isBaseType
     */
    public boolean isBaseType() {
        return false;
    }

    public boolean isArrayType() {
        return false;
    }

    public boolean isClassType() {
        return false;
    }

    public boolean isFuncType() {
        return false;
    }

    public boolean isVoidType() {
        return false;
    }

    public boolean noError() {
        return true;
    }

    public boolean hasError() {
        return !noError();
    }

    /**
     * Tell if this type <em>is subtype of</em> another type.
     * <p>
     * Let {@code t1} {@literal <:} {@code t2} denote that type {@code t1} is subtype of {@code t2}. Rules:
     * <ol>
     *     <li>reflexive: {@code t} {@literal <:} {@code t}</li>
     *     <li>transitive: {@code t1} {@literal <:} {@code t3} if
     *          {@code t1} {@literal <:} {@code t2} and {@code t2} {@literal <:} {@code t3}</li>
     *     <li>error is special: {@code error} {@literal <:} {@code t}, {@code t} {@literal <:} {@code error}</li>
     *     <li>null is an object: {@code null} {@literal <:} {@code class c} for every class {@code c}</li>
     *     <li>class inheritance: {@code class c1} {@literal <:} {@code class c2} if {@code c1} extends {@code c2}</li>
     *     <li>function: {@code (t1, t2, ..., tn) => t} {@literal <:} {@code (s1, s2, ..., sn) => s} if
     *          {@code t} {@literal <:} {@code s} and {@code si} {@literal <:} {@code ti} for every {@code i}</li>
     * </ol>
     *
     * @param that another type
     * @return subtype checking result
     */
    public abstract boolean subtypeOf(Type that);

    /**
     * Tell if two types are equivalent.
     *
     * @param that another type
     * @return type equivalent checking result
     */
    public abstract boolean eq(Type that);

    public abstract String toString();
    
    public static Type merge(Type a, Type b, Pos pos) {
    	//System.out.println("a "+a);
		//System.out.println("b "+b+"\n");
    	
    	if (a.eq(BuiltInType.ERROR) || b.eq(BuiltInType.ERROR))
    		return BuiltInType.ERROR;
    	if (a.eq(BuiltInType.VOID) ^ b.eq(BuiltInType.VOID))
    		return BuiltInType.ERROR;
    	Type ret = null;
    	
    	if (a instanceof BuiltInType && b instanceof BuiltInType) {
    		if (a.eq(b)) ret = a;
    	} else if (a instanceof ClassType && b instanceof ClassType) {
    		ret = ClassType.lca((ClassType)a, (ClassType)b);
    	} else if (a instanceof ArrayType && b instanceof ArrayType) {
    		if (a.eq(b)) ret = a;
    	} else if (a instanceof FunType && b instanceof FunType) {
    		ret = FunType.lca((FunType)a, (FunType)b, pos);
    	}
    	return ret == null ? BuiltInType.ERROR : ret;
    }
}
