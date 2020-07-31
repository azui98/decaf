package decaf.frontend.type;

import java.util.ArrayList;
import java.util.List;

import decaf.frontend.symbol.MethodSymbol;
import decaf.frontend.tree.Pos;
import decaf.frontend.tree.Tree.TypeLit;

/**
 * Function type.
 */
public final class FunType extends Type {

    public Type returnType;

    public final List<Type> argTypes;
    public MethodSymbol refMethod;

    public FunType(Type returnType, List<Type> argTypes) {
        this.returnType = returnType;
        this.argTypes = argTypes;
        this.refMethod = null;
    }
    
    public FunType(Type returnType, List<Type> argTypes, MethodSymbol ref) {
    	this.returnType = returnType;
        this.argTypes = argTypes;
        this.refMethod = ref;
    }
    
    public int arity() {
        return argTypes.size();
    }

    @Override
    public boolean subtypeOf(Type type) {
        if (type.eq(BuiltInType.ERROR)) {
            return true;
        }
        if (!type.isFuncType()) {
            return false;
        }

        // Recall: (t1, t2, ..., tn) => t <: (s1, s2, ..., sn) => s 
        //         if t <: s and si <: ti for every i
        FunType that = (FunType) type;
        if (!this.returnType.subtypeOf(that.returnType) || this.arity() != that.arity()) return false;
        var thisArg = this.argTypes.iterator();
        var thatArg = that.argTypes.iterator();
        while (thisArg.hasNext()) {
            if (!thatArg.next().subtypeOf(thisArg.next())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean eq(Type type) {
        if (!type.isFuncType()) return false;
        var that = (FunType) type;
        if (!this.returnType.eq(that.returnType) || this.arity() != that.arity()) return false;
        var thisArg = this.argTypes.iterator();
        var thatArg = that.argTypes.iterator();
        while (thisArg.hasNext()) {
            if (!thatArg.next().eq(thisArg.next())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        var sb = new StringBuilder();
        if (argTypes.isEmpty()) {
            sb.append("()");
        } else if (argTypes.size() == 1) {
            var arg = argTypes.get(0).toString();
            if (argTypes.get(0).isFuncType()) {
                arg = "(" + arg + ")";
            }
            sb.append(arg);
        } else {
            sb.append('(');
            for (int i = 0; i < argTypes.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(argTypes.get(i));
            }
            sb.append(')');
        }
        sb.append(" => ");
        sb.append(returnType);
        return sb.toString();
    }

    @Override
    public boolean isFuncType() {
        return true;
    }
    
    /**
     * merge two functions
     * return null if failed
     */
    public static FunType lca(FunType a, FunType b, Pos pos) {
    	if (a == null || b == null)
    		return null;
    	if (a.argTypes.size() != b.argTypes.size())
    		return null;
    	
    	Type cmRet = null, aRet = a.returnType, bRet = b.returnType;
    	
    	if (aRet instanceof BuiltInType && bRet instanceof BuiltInType) {
    		if (aRet.eq(bRet)) cmRet = aRet;
    	} else if (aRet instanceof ClassType && bRet instanceof ClassType) {
    		cmRet = ClassType.lca((ClassType)aRet, (ClassType)bRet);
    	} else if (aRet instanceof ArrayType && bRet instanceof ArrayType) {
    		if (aRet.eq(bRet)) cmRet = aRet;
    	} else if (aRet instanceof FunType && bRet instanceof FunType) {
    		cmRet = lca((FunType)aRet, (FunType)bRet, pos);
    	} else if (aRet.eq(BuiltInType.NULL) && bRet instanceof ClassType) {
    		cmRet = bRet;
    	} else if (bRet.eq(BuiltInType.NULL) && aRet instanceof ClassType) {
    		cmRet = aRet;
    	}
    	
    	if (cmRet == null) return null;
    	var cmArgTypes = new ArrayList<Type>();
    	for (int i = 0; i < a.argTypes.size(); i++) {
    		Type cmArg = null, ai = a.argTypes.get(i), bi = b.argTypes.get(i);
    		if (ai instanceof BuiltInType && bi instanceof BuiltInType) {
        		if (ai.eq(bi)) cmArg = ai;
        	} else if (ai instanceof ClassType && bi instanceof ClassType) {
        		cmArg = ClassType.highestCommonSon((ClassType)ai, (ClassType)bi);
        	} else if (ai instanceof ArrayType && bi instanceof ArrayType) {
        		if (ai.eq(bi)) cmArg = ai;
        	} else if (ai instanceof FunType && bi instanceof FunType) {
        		cmArg = HighestCommonSon((FunType)ai, (FunType)bi, pos);
        	}
    		
    		if (cmArg == null) return null;
    		cmArgTypes.add(cmArg);
    	}
    	
    	return new FunType(cmRet, cmArgTypes);
    }
    
    /**
     * Commen Son FunType
     */
    public static FunType HighestCommonSon(FunType a, FunType b, Pos pos) {
    	if (a == null || b == null)
    		return null;
    	if (a.argTypes.size() != b.argTypes.size())
    		return null;
    	
    	Type cmRet = null, aRet = a.returnType, bRet = b.returnType;
    	if (aRet instanceof BuiltInType && bRet instanceof BuiltInType) {
    		if (aRet.eq(bRet)) cmRet = aRet;
    	} else if (aRet instanceof ClassType && bRet instanceof ClassType) {
    		cmRet = ClassType.highestCommonSon((ClassType)aRet, (ClassType)bRet);
    	} else if (aRet instanceof ArrayType && bRet instanceof ArrayType) {
    		if (aRet.eq(bRet)) cmRet = aRet;
    	} else if (aRet instanceof FunType && bRet instanceof FunType) {
    		cmRet = HighestCommonSon((FunType)aRet, (FunType)bRet, pos);
    	} else if (aRet.eq(BuiltInType.NULL) && bRet instanceof ClassType) {
    		cmRet = bRet;
    	} else if (bRet.eq(BuiltInType.NULL) && aRet instanceof ClassType) {
    		cmRet = aRet;
    	}
    	
    	if (cmRet == null) return null;
    	var cmArgTypes = new ArrayList<Type>();
    	for (int i = 0; i < a.argTypes.size(); i++) {
    		Type cmArg = null, ai = a.argTypes.get(i), bi = b.argTypes.get(i);
    		
    		if (ai instanceof BuiltInType && bi instanceof BuiltInType) {
        		if (ai.eq(bi)) cmArg = ai;
        	} else if (ai instanceof ClassType && bi instanceof ClassType) {
        		cmArg = ClassType.lca((ClassType)ai, (ClassType)bi);
        	} else if (ai instanceof ArrayType && bi instanceof ArrayType) {
        		if (ai.eq(bi)) cmArg = ai;
        	} else if (ai instanceof FunType && bi instanceof FunType) {
        		cmArg = lca((FunType)ai, (FunType)bi, pos);
        	}
    		if (cmArg == null) return null;
    		cmArgTypes.add(cmArg);
    	}
    	
    	return new FunType(cmRet, cmArgTypes);
    }
}
