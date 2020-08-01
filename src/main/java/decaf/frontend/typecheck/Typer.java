package decaf.frontend.typecheck;

import decaf.driver.Config;
import decaf.driver.Phase;
import decaf.driver.error.*;
import decaf.frontend.scope.LambdaScope;
import decaf.frontend.scope.ScopeStack;
import decaf.frontend.symbol.ClassSymbol;
import decaf.frontend.symbol.LambdaSymbol;
import decaf.frontend.symbol.MethodSymbol;
import decaf.frontend.symbol.VarSymbol;
import decaf.frontend.symbol.Symbol;
import decaf.frontend.tree.Pos;
import decaf.frontend.tree.Tree;
import decaf.frontend.type.ArrayType;
import decaf.frontend.type.BuiltInType;
import decaf.frontend.type.ClassType;
import decaf.frontend.type.FunType;
import decaf.frontend.type.Type;
import decaf.lowlevel.log.IndentPrinter;
import decaf.printing.PrettyScope;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Stack;

/**
 * The typer phase: type check abstract syntax tree and annotate nodes with inferred (and checked) types.
 */
public class Typer extends Phase<Tree.TopLevel, Tree.TopLevel> implements TypeLitVisited {	
    public Typer(Config config) {
        super("typer", config);
    }

    @Override
    public Tree.TopLevel transform(Tree.TopLevel tree) {
        var ctx = new ScopeStack(tree.globalScope);
        tree.accept(this, ctx);
        return tree;
    }

    @Override
    public void onSucceed(Tree.TopLevel tree) {
        if (config.target.equals(Config.Target.PA2)) {
            var printer = new PrettyScope(new IndentPrinter(config.output));
            printer.pretty(tree.globalScope);
            printer.flush();
        }
    }

    @Override
    public void visitTopLevel(Tree.TopLevel program, ScopeStack ctx) {
        for (var clazz : program.classes) {
            clazz.accept(this, ctx);
        }
    }

    @Override
    public void visitClassDef(Tree.ClassDef clazz, ScopeStack ctx) {
        ctx.open(clazz.symbol.scope);
        for (var field : clazz.fields) {
            field.accept(this, ctx);
        }
        ctx.close();
    }

    @Override
    public void visitMethodDef(Tree.MethodDef method, ScopeStack ctx) {
    	if (method.body.isEmpty()) return; // abstract method
    	
        ctx.open(method.symbol.scope);
        method.body.get().accept(this, ctx); // -new
        if (!method.symbol.type.returnType.isVoidType() && !method.body.get().returns) { // -new
            issue(new MissingReturnError(method.body.get().pos)); // -new
        }
        ctx.close();
    }

    /**
     * To determine if a break statement is legal or not, we need to know if we are inside a loop, i.e.
     * loopLevel {@literal >} 1?
     * <p>
     * Increase this counter when entering a loop, and decrease it when leaving a loop.
     */
    private int loopLevel = 0;

    @Override
    public void visitBlock(Tree.Block block, ScopeStack ctx) {
        ctx.open(block.scope);
        
        Type expected = null;
        boolean retForMethod = ctx.retForMethod();
        if (retForMethod) {
        	expected = ctx.currentMethod().type.returnType;
        }
        for (var stmt : block.stmts) {
            stmt.accept(this, ctx);
            if (stmt instanceof Tree.Return) {
            	
            	var actual = ((Tree.Return)stmt).expr.map(e -> e.type).orElse(BuiltInType.VOID);
            	if (retForMethod) { // return for a method, return-type is fixed
            		if (actual.noError() && !actual.subtypeOf(expected)) {
            			issue(new BadReturnTypeError(stmt.pos, expected.toString(), actual.toString()));
            		}
                } else { // return for a lambda-expr, return-type shall be dynamically determined
                	
                	var lambda = ctx.currentLambda();
                	if (lambda.body.isFirstType) {
                		lambda.type.returnType = actual;
                		lambda.body.isFirstType = false;
                	}
                	else
                		lambda.type.returnType = Type.merge(lambda.type.returnType, actual, stmt.pos);
                }
            }
        }
        ctx.close();
        block.returns = !block.stmts.isEmpty() && block.stmts.get(block.stmts.size() - 1).returns;
    }
    
    
    @Override
    public void visitLambdaBlock(Tree.LambdaBlock block, ScopeStack ctx) {
        ctx.open(block.scope);
        var cur = ctx.currentLambda();
        
        for (var stmt : block.stmts) {
            stmt.accept(this, ctx);
            if (stmt instanceof Tree.Return) {
            	Type type = ((Tree.Return)stmt).expr.map(e -> e.type).orElse(BuiltInType.VOID);
            	if (block.isFirstType) {
            		cur.type.returnType = type;
            		block.isFirstType = false;
            		//System.out.println(stmt.pos);
            		//System.out.println("1: " + cur.type.returnType + "\n");
            	}
            	else {
            		//System.out.println(stmt.pos);
            		cur.type.returnType = Type.merge(cur.type.returnType, type, stmt.pos);
            	}
            }
        }
        
        if (block.isFirstType)
        	cur.type.returnType = BuiltInType.VOID;
        
        var returnType = cur.type.returnType;
        block.returnType = returnType;
        block.returns = !block.stmts.isEmpty() && block.stmts.get(block.stmts.size() - 1).returns;
        
        if (!returnType.eq(BuiltInType.VOID) && !block.returns) {
			issue(new MissingReturnError(block.pos));
		}
        
        if (returnType.eq(BuiltInType.ERROR))
        	issue(new IncompatRetType(block.pos));
        
        ctx.close();
    }

    @Override
    public void visitAssign(Tree.Assign stmt, ScopeStack ctx) {
        stmt.lhs.accept(this, ctx);
        stmt.rhs.accept(this, ctx);
        var lt = stmt.lhs.type;
        var rt = stmt.rhs.type;
        
        Symbol def = null;
        var lhs = stmt.lhs;
        if (lhs instanceof Tree.VarSel) {
        	def = ((Tree.VarSel) lhs).symbol;
        	if (def == null) def = lhs.refMethod;
        	if (def == null) def = ((Tree.VarSel) lhs).methodSymbol;
        }
        else if (lhs instanceof Tree.IndexSel) def = ((Tree.IndexSel) lhs).getSymbol();
        
        if (def != null && def.isMethodSymbol()) {
        	if (!stmt.lhs.type.eq(BuiltInType.ERROR))
        		issue(new AssignToMethodError(stmt.pos, def.name));
        }
        else if (def != null && ctx.currentLambda() != null) {
        	if (!ctx.IsInnerScope(def.domain(), ctx.currentLambda().scope)) {
        		if (!(stmt.lhs instanceof Tree.IndexSel) && !def.domain().isClassScope()) {
        			issue(new AssignCapturedVarError(stmt.pos));
        		}
        	}
        	//System.out.println();
        }

        if (lt.noError() && !rt.subtypeOf(lt)) {
            issue(new IncompatBinOpError(stmt.pos, lt.toString(), "=", rt.toString()));
        }
    }

    @Override
    public void visitExprEval(Tree.ExprEval stmt, ScopeStack ctx) {
        stmt.expr.accept(this, ctx);
    }


    @Override
    public void visitIf(Tree.If stmt, ScopeStack ctx) {
        checkTestExpr(stmt.cond, ctx);
        stmt.trueBranch.accept(this, ctx);
        stmt.falseBranch.ifPresent(b -> b.accept(this, ctx));
        // if-stmt returns a value iff both branches return
        stmt.returns = stmt.trueBranch.returns && stmt.falseBranch.isPresent() && stmt.falseBranch.get().returns;
    }

    @Override
    public void visitWhile(Tree.While loop, ScopeStack ctx) {
        checkTestExpr(loop.cond, ctx);
        loopLevel++;
        loop.body.accept(this, ctx);
        loopLevel--;
    }

    @Override
    public void visitFor(Tree.For loop, ScopeStack ctx) {
        ctx.open(loop.scope);
        loop.init.accept(this, ctx);
        checkTestExpr(loop.cond, ctx);
        loop.update.accept(this, ctx);
        loopLevel++;
        for (var stmt : loop.body.stmts) {
            stmt.accept(this, ctx);
            if (stmt instanceof Tree.Return && ctx.currentLambda() != null){
            	// return for a lambda-expr, return-type shall be dynamically determined
            	var lambda = ctx.currentLambda();
            	var actual = ((Tree.Return)stmt).expr.map(e -> e.type).orElse(BuiltInType.VOID);
            	if (lambda.body.isFirstType) {
            		lambda.type.returnType = actual;
            		lambda.body.isFirstType = false;
            	}
            	else
            		lambda.type.returnType = Type.merge(lambda.type.returnType, actual, stmt.pos);
            }
        }
        loopLevel--;
        ctx.close();
    }

    @Override
    public void visitBreak(Tree.Break stmt, ScopeStack ctx) {
        if (loopLevel == 0) {
            issue(new BreakOutOfLoopError(stmt.pos));
        }
    }

    @Override
    public void visitReturn(Tree.Return stmt, ScopeStack ctx) {
        //var expected = ctx.currentMethod().type.returnType;
        stmt.expr.ifPresent(e -> e.accept(this, ctx));
        /*var actual = stmt.expr.map(e -> e.type).orElse(BuiltInType.VOID);
        if (actual.noError() && !actual.subtypeOf(expected)) {
            issue(new BadReturnTypeError(stmt.pos, expected.toString(), actual.toString()));
        }*/
        stmt.returns = stmt.expr.isPresent();
    }

    @Override
    public void visitPrint(Tree.Print stmt, ScopeStack ctx) {
        int i = 0;
        for (var expr : stmt.exprs) {
            expr.accept(this, ctx);
            i++;
            if (expr.type.noError() && !expr.type.isBaseType()) {
                issue(new BadPrintArgError(expr.pos, Integer.toString(i), expr.type.toString()));
            }
        }
    }

    private void checkTestExpr(Tree.Expr expr, ScopeStack ctx) {
        expr.accept(this, ctx);
        if (expr.type.noError() && !expr.type.eq(BuiltInType.BOOL)) {
            issue(new BadTestExpr(expr.pos));
        }
    }

    // Expressions

    @Override
    public void visitIntLit(Tree.IntLit that, ScopeStack ctx) {
        that.type = BuiltInType.INT;
    }

    @Override
    public void visitBoolLit(Tree.BoolLit that, ScopeStack ctx) {
        that.type = BuiltInType.BOOL;
    }

    @Override
    public void visitStringLit(Tree.StringLit that, ScopeStack ctx) {
        that.type = BuiltInType.STRING;
    }

    @Override
    public void visitNullLit(Tree.NullLit that, ScopeStack ctx) {
        that.type = BuiltInType.NULL;
    }

    @Override
    public void visitReadInt(Tree.ReadInt readInt, ScopeStack ctx) {
        readInt.type = BuiltInType.INT;
    }

    @Override
    public void visitReadLine(Tree.ReadLine readStringExpr, ScopeStack ctx) {
        readStringExpr.type = BuiltInType.STRING;
    }
    
    @Override
    public void visitLambda(Tree.Lambda lambda, ScopeStack ctx) {
    	
    	//System.out.println(lambda.pos);
    	ctx.open(lambda.symbol.scope);
    	
    	var argTypes = new ArrayList<Type>();
    	var symbol = lambda.symbol;
    	
    	FunType type;
    	assert symbol != null;
    	
		for (var argDef : lambda.params) {
			argTypes.add(argDef.symbol.type);
		}
		
    	if (lambda.rhs != null) { // fun(int x) => x+1;
    		ctx.open(lambda.exprScope);
    		lambda.rhs.accept(this, ctx);
    		type = new FunType(lambda.rhs.type, argTypes);
    		//System.out.println(type);
    		lambda.type = type;
    		symbol.type = type;
    		ctx.close();
    	}
    	else { // fun(int x) { return x+1; }
    		lambda.body.accept(this, ctx);
    		type = new FunType(lambda.symbol.type.returnType, argTypes);
    		lambda.type = type;
    		symbol.type = type;
    		
    	}
    	
    	ctx.close();
    }

    @Override
    public void visitUnary(Tree.Unary expr, ScopeStack ctx) {
        expr.operand.accept(this, ctx);
        var t = expr.operand.type;
        if (t.noError() && !compatible(expr.op, t)) {
            // Only report this error when the operand has no error, to avoid nested errors flushing.
            issue(new IncompatUnOpError(expr.pos, Tree.opStr(expr.op), t.toString()));
        }

        // Even when it doesn't type check, we could make a fair guess based on the operator kind.
        // Let's say the operator is `-`, then one possibly wants an integer as the operand.
        // Once he/she fixes the operand, according to our type inference rule, the whole unary expression
        // must have type int! Thus, we simply _assume_ it has type int, rather than `NoType`.
        expr.type = resultTypeOf(expr.op);
    }

    public boolean compatible(Tree.UnaryOp op, Type operand) {
        return switch (op) {
            case NEG -> operand.eq(BuiltInType.INT); // if e : int, then -e : int
            case NOT -> operand.eq(BuiltInType.BOOL); // if e : bool, then !e : bool
        };
    }

    public Type resultTypeOf(Tree.UnaryOp op) {
        return switch (op) {
            case NEG -> BuiltInType.INT;
            case NOT -> BuiltInType.BOOL;
        };
    }

    @Override
    public void visitBinary(Tree.Binary expr, ScopeStack ctx) {
    	
        expr.lhs.accept(this, ctx);
        expr.rhs.accept(this, ctx);
        var t1 = expr.lhs.type;
        var t2 = expr.rhs.type;
        if (t1.noError() && t2.noError() && !compatible(expr.op, t1, t2)) {
            issue(new IncompatBinOpError(expr.pos, t1.toString(), Tree.opStr(expr.op), t2.toString()));
        }
        expr.type = resultTypeOf(expr.op);
    }

    public boolean compatible(Tree.BinaryOp op, Type lhs, Type rhs) {
        if (op.compareTo(Tree.BinaryOp.ADD) >= 0 && op.compareTo(Tree.BinaryOp.MOD) <= 0) { // arith
            // if e1, e2 : int, then e1 + e2 : int
            return lhs.eq(BuiltInType.INT) && rhs.eq(BuiltInType.INT);
        }

        if (op.equals(Tree.BinaryOp.AND) || op.equals(Tree.BinaryOp.OR)) { // logic
            // if e1, e2 : bool, then e1 && e2 : bool
            return lhs.eq(BuiltInType.BOOL) && rhs.eq(BuiltInType.BOOL);
        }

        if (op.equals(Tree.BinaryOp.EQ) || op.equals(Tree.BinaryOp.NE)) { // eq
            // if e1 : T1, e2 : T2, T1 <: T2 or T2 <: T1, then e1 == e2 : bool
            return lhs.subtypeOf(rhs) || rhs.subtypeOf(lhs);
        }

        // compare
        // if e1, e2 : int, then e1 > e2 : bool
        return lhs.eq(BuiltInType.INT) && rhs.eq(BuiltInType.INT);
    }

    public Type resultTypeOf(Tree.BinaryOp op) {
        if (op.compareTo(Tree.BinaryOp.ADD) >= 0 && op.compareTo(Tree.BinaryOp.MOD) <= 0) { // arith
            return BuiltInType.INT;
        }
        return BuiltInType.BOOL;
    }

    @Override
    public void visitNewArray(Tree.NewArray expr, ScopeStack ctx) {
        expr.elemType.accept(this, ctx);
        expr.length.accept(this, ctx);
        var et = expr.elemType.type;
        var lt = expr.length.type;

        if (et.isVoidType()) {
            issue(new BadArrElementError(expr.elemType.pos));
            expr.type = BuiltInType.ERROR;
        } else {
            expr.type = new ArrayType(et);
        }

        if (lt.noError() && !lt.eq(BuiltInType.INT)) {
            issue(new BadNewArrayLength(expr.length.pos));
        }
    }

    @Override
    public void visitNewClass(Tree.NewClass expr, ScopeStack ctx) {
        var clazz = ctx.lookupClass(expr.clazz.name);
        if (clazz.isPresent()) {
            expr.symbol = clazz.get();
            expr.type = expr.symbol.type;
            if (expr.symbol.isAbstract())
            	issue(new CannotInstanceAbstract(expr.pos, expr.clazz.name));
        } else {
            issue(new ClassNotFoundError(expr.pos, expr.clazz.name));
            expr.type = BuiltInType.ERROR;
        }
    }

    @Override
    public void visitThis(Tree.This expr, ScopeStack ctx) {
        if (ctx.currentMethod().isStatic()) {
            issue(new ThisInStaticFuncError(expr.pos));
        }
        expr.type = ctx.currentClass().type;
    }
    
    public Symbol getMethod(String className, String methodName, boolean thisClass, ScopeStack ctx) {
    	var clazz = thisClass ? ctx.currentClass() : ctx.getClass(className);
        var symbol = clazz.scope.lookup(methodName);
        if (symbol.isPresent()) return symbol.get();
        return null;
    }

    private boolean allowClassNameVar = false;

    @Override
    public void visitVarSel(Tree.VarSel expr, ScopeStack ctx) {
    	
        if (expr.receiver.isEmpty()) {
            // Variable, which should be complicated since a legal variable could refer to a local var,
            // a visible member var, and a class name.
            var symbol = ctx.lookupBefore(expr.name, localVarDefPos.orElse(expr.pos));
            //System.out.println("key = " + expr.name + " ; symbol = " + symbol);
            //System.out.println();
            if (symbol.isPresent()) {
                if (symbol.get().isVarSymbol()) {
                    var var = (VarSymbol) symbol.get();
                    expr.symbol = var;
                    expr.type = var.type;/*
                    if (inLambda && var.domain() != ctx.currentLambda().scope) {
                    	System.out.println(var.domain() +" | "+ ctx.currentLambda().scope);
                    	issue(new AssignCapturedVarError(expr.pos));
                    	expr.type = BuiltInType.ERROR;
                    	return;
                    }*/
                    
                    if (expr.symbol.isUnfinishedLambda) {
                    	expr.type = BuiltInType.ERROR;
                    	issue(new UndeclVarError(expr.pos, expr.variable.name));
                    	return;
                    }
                    
                    if (var.isMemberVar()) {
                        if (ctx.currentMethod().isStatic()) {
                            issue(new RefNonStaticError(expr.pos, ctx.currentMethod().name, expr.name));
                        } else {
                            expr.setThis();
                        }
                    }
                    return;
                }
                
                if (symbol.get().isMethodSymbol()) { // Variables can refer to methods
                	var method = (MethodSymbol) symbol.get();
                	expr.refMethod = method;
                	expr.methodSymbol = method;
                	expr.type = method.type;
                	if (ctx.currentMethod().isStatic() && !method.isStatic()) {
                		issue(new RefNonStaticError(expr.pos, ctx.currentMethod().name, expr.name));
                	} else if (!method.isStatic())
                		expr.setThis();
                	return;
                }

                if (symbol.get().isClassSymbol() && allowClassNameVar) { // special case: a class name
                    var clazz = (ClassSymbol) symbol.get();
                    expr.type = clazz.type;
                    expr.isClassName = true;
                    return;
                }
            }
            //else System.out.println("fuck!");

            expr.type = BuiltInType.ERROR;
            issue(new UndeclVarError(expr.pos, expr.name));
            return;
        }

        // has receiver
        var receiver = expr.receiver.get();
        allowClassNameVar = true;
        receiver.accept(this, ctx);
        allowClassNameVar = false;
        var rt = receiver.type;
        expr.type = BuiltInType.ERROR;

        if (receiver instanceof Tree.VarSel) {
            var v1 = (Tree.VarSel) receiver;
            //System.out.println(v1.name);
            if (v1.isClassName) {
            	//System.out.println(expr.pos);
                // special case like MyClass.foo: report error cannot access field 'foo' from 'class : MyClass'
            	var called = expr.variable.name;
            	var u = ctx.lookup(v1.variable.name);
            	if (u.isPresent() && u.get() instanceof ClassSymbol) {
            		var v = (ClassSymbol)u.get();
            		var z = v.scope.get(called);
            		//System.out.println(expr.pos);
            		if (z == null) {
            			issue(new FieldNotFoundError(expr.pos, called, "class "+v1.variable.name));
            			return;
            		}
            		if (z != null && z.isMethodSymbol()) {
            			expr.methodSymbol = (MethodSymbol)z;
            			if (((MethodSymbol)z).isStatic()) {
            				expr.type = ((MethodSymbol)z).type;
            				expr.methodSymbol = (MethodSymbol)z;
            				//System.out.println("z: "+z);
            				return;
            			}
            			else expr.type = BuiltInType.ERROR;
            		}
            	}
            	
            	issue(new NotClassFieldError(expr.pos, expr.name, ctx.getClass(v1.name).type.toString()));
                return;
            }
        }
        
        if (rt.isArrayType()) {
        	if (expr.variable.name.equals("length"))
        		expr.isArrayLength = true;
        	expr.type = new FunType(BuiltInType.INT, new ArrayList<Type>());
        	return;
        }

        if (!rt.noError()) {
            return;
        }

        if (!rt.isClassType()) {
            issue(new NotClassFieldError(expr.pos, expr.name, rt.toString()));
            return;
        }

        var ct = (ClassType) rt;
        var field = ctx.getClass(ct.name).scope.lookup(expr.name);
        //System.out.println(field);
        if (field.isPresent() && field.get().isVarSymbol()) {
            var var = (VarSymbol) field.get();
            if (var.isMemberVar()) {
                expr.symbol = var;
                expr.type = var.type;
                if (!ctx.currentClass().type.subtypeOf(var.getOwner().type)) {
                    // member vars are protected
                    issue(new FieldNotAccessError(expr.pos, expr.name, ct.toString()));
                }
            }
        }
        else if (field.isPresent() && field.get().isMethodSymbol()) {
        	var var = (MethodSymbol) field.get();
        	if (var.isAbstract()) {
        		// TODO abstract
        		expr.methodSymbol = var;
        		expr.type = var.type;
        	} else if (var.isStatic()) {
        		// TODO static
        		expr.methodSymbol = var;
        		expr.type = var.type;
        	} else {
        		expr.methodSymbol = var;
        		expr.type = var.type;
        	}
        }
        else if (field.isEmpty()) {
            issue(new FieldNotFoundError(expr.pos, expr.name, ct.toString()));
        }
        else {
            issue(new NotClassFieldError(expr.pos, expr.name, ct.toString()));
        }
    }
    
    private void callMethod(String owner, Tree.Call call, MethodSymbol method, ScopeStack ctx,
    		boolean requireStatic, boolean thisClass) {
    	call.symbol = method;
    	call.type = method.type.returnType; // TODO make sure this won't go wrong in case of LambdaExpr
    	if (requireStatic && !method.isStatic()) {
    		//System.out.println("543");
            issue(new NotClassFieldError(call.pos, call.methodName, owner));
            return;
        }
    	
    	// Cannot call this's member methods in a static method
        if (thisClass && ctx.currentMethod().isStatic() && !method.isStatic()) {
            issue(new RefNonStaticError(call.pos, ctx.currentMethod().name, method.name));
        }
        
        // typing args
        var args = call.args;
        for (var arg : args) {
            arg.accept(this, ctx);
        }

        // check signature compatibility
        if (method.type.arity() != args.size()) {
            issue(new BadArgCountError(call.pos, method.name, method.type.arity(), args.size()));
        }
        var iter1 = method.type.argTypes.iterator();
        var iter2 = call.args.iterator();
        for (int i = 1; iter1.hasNext() && iter2.hasNext(); i++) {
            Type t1 = iter1.next();
            Tree.Expr e = iter2.next();
            Type t2 = e.type;
            if (t2.noError() && !t2.subtypeOf(t1)) {
            	//System.out.println("1");
                issue(new BadArgTypeError(e.pos, i, t2.toString(), t1.toString()));
            }
        }
    }

    @Override
    public void visitIndexSel(Tree.IndexSel expr, ScopeStack ctx) {
        expr.array.accept(this, ctx);
        expr.index.accept(this, ctx);
        var at = expr.array.type;
        var it = expr.index.type;

        if (!at.isArrayType()) {
        	if (!at.eq(BuiltInType.ERROR)) {
        		//System.out.println(expr.array.pos);
        		issue(new NotArrayError(expr.array.pos));
        	}
            expr.type = BuiltInType.ERROR;
            return;
        }

        expr.type = ((ArrayType) at).elementType;
        if (!it.eq(BuiltInType.INT)) {
            issue(new SubNotIntError(expr.pos));
        }
    }

    @Override
    public void visitGoCall(Tree.GoCall goCall, ScopeStack ctx) {
        goCall.callExpr.accept(this, ctx);
    }

    @Override
    public void visitCall(Tree.Call expr, ScopeStack ctx) {
        expr.type = BuiltInType.ERROR;
        Type rt;
        boolean thisClass = false;
        var caller = expr.caller;

        caller.accept(this, ctx);
        for (var arg : expr.args)
        	arg.accept(this, ctx);
        
        // First, check if calling length()
        if (caller instanceof Tree.VarSel) {
        	var varSel = (Tree.VarSel) caller;
        	if (varSel.receiver.isPresent() && varSel.variable.name.equals("length")) {
        		rt = varSel.receiver.get().type;
        		if (rt.isArrayType()) { // pattern correct
        			if (!expr.args.isEmpty()) {
                        issue(new BadLengthArgError(expr.pos, expr.args.size()));
        			}
                    expr.isArrayLength = true;
                    expr.type = BuiltInType.INT;
                    return;
        		}
        	}
        }
        
        if (caller.type.isFuncType()) {
        	var ct = (FunType)caller.type;
        	
        	var needed = ct.argTypes;
        	var given = expr.args;
        	
        	if (caller instanceof Tree.VarSel) { // calling a method by reference
        		var method = ((Tree.LValue)caller).refMethod;
        		if (method != null) {
        			callMethod(method.name, expr, method, ctx, false, method.isStatic());
        			return;
        		}
        	}
        	if (caller instanceof Tree.VarSel) {
        		var varSel = (Tree.VarSel)caller;
        		
        		if (varSel.receiver.isPresent()) { 
                    var recv = varSel.receiver.get();
                    rt = recv.type;
                    if (recv instanceof Tree.VarSel) {
                    	var v1 = (Tree.VarSel)recv;
                    	if (v1.isClassName) {
                    		typeCall(expr, false, v1.name, ctx, true);
                            return;
                    	}
                    }
                } else {
                	// f(x), f may be local LambdaExpr or method in this
                	//System.out.println(caller);
                	var var = ctx.lookupBefore(varSel.name, varSel.pos);
                	if (var.isPresent() && var.get() instanceof VarSymbol) { // local LambdaExpr
                		if (needed.size() != given.size()) {
        	        		issue(new FuncArgCountError(expr.pos, varSel.name, needed.size(), given.size()));
        	        	}
        	        	// Check types of Args
        	        	var iter1 = needed.iterator();
        	            var iter2 = given.iterator();
        	            for (int i = 1; iter1.hasNext() && iter2.hasNext(); i++) {
        	                Type t1 = iter1.next();
        	                Tree.Expr e = iter2.next();
        	                Type t2 = e.type;
        	                if (t2.noError() && !t2.subtypeOf(t1)) {
        	                    issue(new BadArgTypeError(e.pos, i, t2.toString(), t1.toString()));
        	                }
        	            }
        	            expr.type = ((FunType)caller.type).returnType;
                		return;
                	}
                	else {
                		//System.out.println(expr.pos);
	                    thisClass = true;
	                    varSel.setThis();
	                    rt = ctx.currentClass().type;
                	}
                }
        		
        		if (rt != null && rt.noError()) {
        			if (rt.isClassType()) {
                        typeCall(expr, thisClass, ((ClassType) rt).name, ctx, false);
                    } else {
                        issue(new NotClassFieldError(expr.pos, expr.methodName, rt.toString()));
                    }
        		}
        	}
        	
        	// Lambda Expr
        	// Check Number of Args
        	else {
	        	if (needed.size() != given.size()) {
	        		issue(new LambdaArgCountError(expr.pos, needed.size(), given.size()));
	        	}
	        	
	        	// Check types of Args
	        	var iter1 = needed.iterator();
	            var iter2 = given.iterator();
	            for (int i = 1; iter1.hasNext() && iter2.hasNext(); i++) {
	                Type t1 = iter1.next();
	                Tree.Expr e = iter2.next();
	                Type t2 = e.type;
	                if (t2.noError() && !t2.subtypeOf(t1)) {
	                    issue(new BadArgTypeError(e.pos, i, t2.toString(), t1.toString()));
	                }
	            }
        	}
            
            expr.type = ((FunType)caller.type).returnType;
        }
        else if (!caller.type.eq(BuiltInType.ERROR)) {
        	issue(new NotCallable(expr.pos, caller.type.toString()));
        }
        
    }

    private void typeCall(Tree.Call call, boolean thisClass, String className, ScopeStack ctx, boolean requireStatic) {
        // First, Find if calling a local var
    	var var = ctx.lookupBefore(call.methodName, call.pos);
    	if (var.isPresent()) {
    		if (var.get() instanceof VarSymbol && var.get().type instanceof FunType) {
    			var fun = (FunType)var.get().type;
    			var args = call.args;
    			call.symbol = var.get();
    			call.type = fun.returnType;
    			
                for (var arg : args) {
                    arg.accept(this, ctx);
                }
    			
    			if (fun.arity() != args.size()) {
    				issue(new BadArgCountError(call.pos, var.get().name, fun.arity(), args.size()));
    				//issue(new LambdaArgCountError(call.pos, fun.arity(), args.size()));
                }
                var iter1 = fun.argTypes.iterator();
                var iter2 = call.args.iterator();
                for (int i = 1; iter1.hasNext() && iter2.hasNext(); i++) {
                    Type t1 = iter1.next();
                    Tree.Expr e = iter2.next();
                    Type t2 = e.type;
                    if (t2.noError() && !t2.subtypeOf(t1)) {
                        issue(new BadArgTypeError(e.pos, i, t2.toString(), t1.toString()));
                    }
                }
                return;
    		}
    	}
    	
    	// Then, Find a method
    	var clazz = thisClass ? ctx.currentClass() : ctx.getClass(className);
        var symbol = clazz.scope.lookup(call.methodName);
        if (symbol.isPresent()) {
            if (symbol.get().isMethodSymbol()) {
                var method = (MethodSymbol) symbol.get();
                call.symbol = method;
                call.type = method.type.returnType;
                
                if (requireStatic && !method.isStatic()) {
                    issue(new NotClassFieldError(call.pos, call.methodName, clazz.type.toString()));
                    return;
                }

                // Cannot call this's member methods in a static method
                if (thisClass && ctx.currentMethod().isStatic() && !method.isStatic()) {
                    issue(new RefNonStaticError(call.pos, ctx.currentMethod().name, method.name));
                }

                // typing args
                var args = call.args;
                for (var arg : args) {
                    arg.accept(this, ctx);
                }

                // check signature compatibility
                if (method.type.arity() != args.size()) {
                    issue(new BadArgCountError(call.pos, method.name, method.type.arity(), args.size()));
                }
                var iter1 = method.type.argTypes.iterator();
                var iter2 = call.args.iterator();
                for (int i = 1; iter1.hasNext() && iter2.hasNext(); i++) {
                    Type t1 = iter1.next();
                    Tree.Expr e = iter2.next();
                    Type t2 = e.type;
                    if (t2.noError() && !t2.subtypeOf(t1)) {
                        issue(new BadArgTypeError(e.pos, i, t2.toString(), t1.toString()));
                    }
                }
            } else {
                issue(new NotClassMethodError(call.pos, call.methodName, clazz.type.toString()));
            }
        } else {
        	/*System.out.println(clazz);
        	System.out.println(symbol);
        	System.out.println(call.pos);*/
            issue(new FieldNotFoundError(call.pos, call.methodName, clazz.type.toString()));
        }
    }

    @Override
    public void visitClassTest(Tree.ClassTest expr, ScopeStack ctx) {
        expr.obj.accept(this, ctx);
        expr.type = BuiltInType.BOOL;

        if (!expr.obj.type.isClassType()) {
            issue(new NotClassError(expr.obj.type.toString(), expr.pos));
        }
        var clazz = ctx.lookupClass(expr.is.name);
        if (clazz.isEmpty()) {
            issue(new ClassNotFoundError(expr.pos, expr.is.name));
        } else {
            expr.symbol = clazz.get();
        }
    }

    @Override
    public void visitClassCast(Tree.ClassCast expr, ScopeStack ctx) {
        expr.obj.accept(this, ctx);

        if (!expr.obj.type.isClassType()) {
            issue(new NotClassError(expr.obj.type.toString(), expr.pos));
        }

        var clazz = ctx.lookupClass(expr.to.name);
        if (clazz.isEmpty()) {
            issue(new ClassNotFoundError(expr.pos, expr.to.name));
            expr.type = BuiltInType.ERROR;
        } else {
            expr.symbol = clazz.get();
            expr.type = expr.symbol.type;
        }
    }

    @Override
    public void visitLocalVarDef(Tree.LocalVarDef stmt, ScopeStack ctx) {
    	
    	// TFunc can only be analyzed after "Namer.java" is executed
    	if (stmt.typeLit.isPresent() && stmt.typeLit.get() instanceof Tree.TFunc) {
    		stmt.typeLit.get().accept(this, ctx);
    		stmt.symbol.type = ((Tree.TFunc)stmt.typeLit.get()).type;
    	}
    	
        if (stmt.initVal.isEmpty()) return;

        var initVal = stmt.initVal.get();
        localVarDefPos = Optional.ofNullable(stmt.id.pos);
        //System.out.println(initVal);
        
        if (initVal instanceof Tree.Lambda)
        	stmt.symbol.isUnfinishedLambda = true;
        
        initVal.accept(this, ctx);
        
        stmt.symbol.isUnfinishedLambda = false;
        
        localVarDefPos = Optional.empty();
        var lt = stmt.symbol.type;
        var rt = initVal.type;
        //System.out.println(initVal);
        
        if (lt == null) {
        	lt = rt; // var x = ...
        	stmt.symbol.type = rt;
        	//System.out.println(stmt.symbol.type);
        }
        
        /*if (rt == null) { // in Lambda Block being defined, see lambda-decl-error2 line14
        	assert initVal instanceof Tree.VarSel;
        	issue(new UndeclVarError(stmt.pos, ((Tree.VarSel)initVal).variable.name));
        	return;
        }*/
        
        //System.out.println(rt);
        if (rt.eq(BuiltInType.VOID)) {
        	issue(new BadVarTypeError(stmt.id.pos, stmt.id.name));
        	stmt.symbol.type = BuiltInType.ERROR;
        }
        
        //System.out.println(lt + " | " + rt);
        
        if (lt.noError() && !rt.subtypeOf(lt)) {
            issue(new IncompatBinOpError(stmt.assignPos, lt.toString(), "=", rt.toString()));
        }
    }

    // Only usage: check if an initializer cyclically refers to the declared variable, e.g. var x = x + 1
    private Optional<Pos> localVarDefPos = Optional.empty();
}
