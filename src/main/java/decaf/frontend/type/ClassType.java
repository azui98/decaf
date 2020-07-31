package decaf.frontend.type;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Optional;

/**
 * Class type.
 */
public final class ClassType extends Type {

    public final String name;

    public final Optional<ClassType> superType;

    public ClassType(String name, ClassType superType) {
        this.name = name;
        this.superType = Optional.of(superType);
    }

    public ClassType(String name) {
        this.name = name;
        this.superType = Optional.empty();
    }

    @Override
    public boolean subtypeOf(Type that) {
        if (that.eq(BuiltInType.ERROR)) {
            return true;
        }

        if (!that.isClassType()) {
            return false;
        }

        var t = this;
        while (true) {
            if (t.eq(that)) return true;
            if (t.superType.isPresent()) t = t.superType.get();
            else break;
        }

        return false;
    }

    @Override
    public boolean eq(Type that) {
        return that.isClassType() && name.equals(((ClassType) that).name);
    }

    @Override
    public boolean isClassType() {
        return true;
    }

    @Override
    public String toString() {
        return "class " + name;
    }
    
    /**
     * Lowest Common Ancestor ClassType of two classes
     * return null if none
     */
    public static ClassType lca(ClassType a, ClassType b) {
    	if (a == null || b == null)
    		return null;
    	var aAncestors = new ArrayList<ClassType>();
    	var bAncestors = new ArrayList<ClassType>();
    	for (var t = a; true; t = t.superType.get()) {
    		aAncestors.add(t);
    		if (t.superType.isEmpty()) break;
    	}
    	for (var t = b; true; t = t.superType.get()) {
    		bAncestors.add(t);
    		if (t.superType.isEmpty()) break;
    	}
    	Collections.reverse(aAncestors);
    	Collections.reverse(bAncestors);
    	ClassType ret = null;
    	for (int i = 0; i < aAncestors.size() && i < bAncestors.size(); i++) {
    		if (aAncestors.get(i).eq(bAncestors.get(i)))
    			ret = aAncestors.get(i);
    		else break;
    	}
    	//System.out.println(a + " | " + b + " : " +ret);
    	return ret;
    }
    
    /**
     * return the one which is in the subtree of the other
     * return null if not satisfied
     */
    public static ClassType highestCommonSon(ClassType a, ClassType b) {
    	if (a == null || b == null)
    		return null;
    	if (a.subtypeOf(b)) return a;
    	if (b.subtypeOf(a)) return b;
    	return null;
    }
}
