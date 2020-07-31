package decaf.frontend.tacgen;

import decaf.frontend.symbol.MethodSymbol;
import decaf.frontend.tree.Tree;
import decaf.frontend.tree.Tree.VarSel;
import decaf.frontend.tree.Visitor;
import decaf.frontend.type.BuiltInType;
import decaf.frontend.type.FunType;
import decaf.lowlevel.instr.Temp;
import decaf.lowlevel.label.Label;
import decaf.lowlevel.tac.FuncVisitor;
import decaf.lowlevel.tac.Intrinsic;
import decaf.lowlevel.tac.ProgramWriter;
import decaf.lowlevel.tac.RuntimeError;
import decaf.lowlevel.tac.TacInstr;
import decaf.lowlevel.tac.TacInstr.Parm;

import java.util.ArrayList;
import java.util.Stack;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * TAC emitter. Traverse the tree and emit TAC.
 * <p>
 * When emitting TAC, we use utility methods from {@link FuncVisitor}, so that we don't bother
 * ourselves understanding the underlying format of TAC instructions.
 * <p>
 * See {@link #emitIfThen} for the usage of {@link Consumer}.
 */
public interface TacEmitter extends Visitor<FuncVisitor> {
	
	ProgramWriter getWriter();

    /**
     * Record the exit labels of loops entered so far. In this way, when we encounter a break statement, we know the
     * exact label we will jump to.
     * <p>
     * Push a label when entering a loop, and pop when leaving a loop.
     */
    Stack<Label> loopExits = new Stack<>();
    
    Stack<FuncVisitor> lambdas = new Stack<>();
    
    /**
     * find a temp is defined in which function
     */
    default int findScope(FuncVisitor t) {
    	for (int i = 0; i < lambdas.size(); i++)
    		if (lambdas.get(i) == t)
    			return i;
    	assert false;
    	return -1;
    }
    
    /**
     * Capture the temp from the function where it was defined
     * and pass it layer by layer to the function where it is being called. <br/> 
     * For example : <br/> 
     * int[] a = new int[5]; <br/> 
     * var f = fun() { <br/> 
     * var g = fun() { a[0] = 1; } <br/> 
     * g(); <br/> 
     * } <br/> 
     * 'a' is called by function 'g', so we should first capture 'a' from current
     * fuction to 'f', and then capture 'a' from 'f' to 'g'.
     * @param t - Temp detected to capture
     */
    default Temp passCaptured(Temp t) {
    	int defIn = findScope(t.getDomain());
    	for (int i = defIn + 1; i < lambdas.size(); i++)
    		t = lambdas.get(i).capture(t);
    	return t;
    }
    
    default Temp passCaptured(Temp t, FuncVisitor cur) {
    	if (t.getDomain() == cur) return t;
    	int defIn = findScope(t.getDomain());
    	int dst = findScope(cur);
    	for (int i = defIn + 1; i <= dst; i++)
    		t = lambdas.get(i).capture(t);
    	return t;
    }
    
    @Override
    default void visitLambda(Tree.Lambda lam, FuncVisitor mv) {
    	
    	var pw = getWriter();
    	String id = "lambda@" + lam.pos;
    	pw.putLambdaLabel(id);
    	var lamFuncVisitor = pw.visitFunc(id, lam.params.size(), true, mv.hasThisArg);
    	//System.out.println(lamFuncVisitor.argCnt());
    	lambdas.add(lamFuncVisitor);
    	int i = 0;
    	for (var param : lam.params) {
            param.symbol.temp = lamFuncVisitor.getArgTemp(i);
            i++;
        }
    	if (lam.rhs != null) {
    		lam.rhs.accept(this, lamFuncVisitor);
    		lamFuncVisitor.visitReturn(lam.rhs.val);
    	} else {
    		//System.out.println("lam + " +lamFuncVisitor);
    		lam.body.accept(this, lamFuncVisitor);
    	}
    	lambdas.pop();
    	lamFuncVisitor.appendCaptured();
    	lamFuncVisitor.visitEnd();
    	pw.buildVTableForLambda(id);
    	var vtbl = mv.visitLoadVTable(id);
    	var capTemps = lamFuncVisitor.capTemps();
    	int capCnt = capTemps.size();
    	var size = mv.visitLoad(8 + 4 * capCnt + (lamFuncVisitor.hasThisArg ? 4 : 0));
        var addr = mv.visitIntrinsicCall(Intrinsic.ALLOCATE, true, size);
        mv.visitStoreTo(addr, vtbl);
        mv.visitStoreTo(addr, 4, mv.visitLoad(capCnt + (lamFuncVisitor.hasThisArg ? 1 : 0)));
        
        int pos = 8;
        if (lamFuncVisitor.hasThisArg) {
        	var zis = mv.getArgTemp(mv.argCnt()-1);
        	//System.out.println();
        	//System.out.println(zis);
        	mv.visitStoreTo(addr, pos, zis);
        	pos += 4;
        }
        for (var cap : capTemps) {
        	mv.visitStoreTo(addr, pos, passCaptured(cap, mv));
        	pos += 4;
        }
    	lam.val = addr;
    }

    @Override
    default void visitBlock(Tree.Block block, FuncVisitor mv) {
        for (var stmt : block.stmts) {
            stmt.accept(this, mv);
        }
    }
    
    @Override
    default void visitLambdaBlock(Tree.LambdaBlock block, FuncVisitor mv) {
        for (var stmt : block.stmts) {
            stmt.accept(this, mv);
        }
    }

    @Override
    default void visitLocalVarDef(Tree.LocalVarDef def, FuncVisitor mv) {
        def.symbol.temp = mv.freshTemp();
        if (def.initVal.isEmpty()) return;
        var initVal = def.initVal.get();
        initVal.accept(this, mv);
        mv.visitAssign(def.symbol.temp, initVal.val);
        /*
        if (initVal.isLValue()) {
        	Tree.LValue t = (Tree.LValue)initVal;
        	//if (t.object != null) 
        }*/
    }

    @Override
    default void visitAssign(Tree.Assign assign, FuncVisitor mv) {
    	/*
        if (assign.rhs instanceof Tree.VarSel) {
        	var r = (Tree.VarSel)assign.rhs;
        	if (r.symbol.object != null) {
        		mv.visitAssign(assign.lhs., src);
        	}
        }*/
    	
        if (assign.lhs instanceof Tree.IndexSel) {
            var indexSel = (Tree.IndexSel) assign.lhs;
            indexSel.array.accept(this, mv);
            indexSel.index.accept(this, mv);
            //System.out.println(indexSel.array.val);
            
            if (indexSel.array.val.getDomain() != mv)
            	indexSel.array.val = mv.capture(indexSel.array.val);
            //System.out.println(indexSel.array.val);
            
            var addr = emitArrayElementAddress(indexSel.array.val, indexSel.index.val, mv);
            assign.rhs.accept(this, mv);
            mv.visitStoreTo(addr, assign.rhs.val);
        } else if (assign.lhs instanceof Tree.VarSel) {
        	var v = (Tree.VarSel) assign.lhs;
            if (v.symbol.isMemberVar()) {
                var object = v.receiver.get();
                object.accept(this, mv);
                assign.rhs.accept(this, mv);
                mv.visitMemberWrite(object.val, v.symbol.getOwner().name, v.name, assign.rhs.val);
            } else { // local or param
                assign.rhs.accept(this, mv);
                mv.visitAssign(v.symbol.temp, assign.rhs.val);
            }
        }
    }

    @Override
    default void visitExprEval(Tree.ExprEval eval, FuncVisitor mv) {
        eval.expr.accept(this, mv);
    }

    @Override
    default void visitIf(Tree.If stmt, FuncVisitor mv) {
        stmt.cond.accept(this, mv);
        Consumer<FuncVisitor> trueBranch = v -> stmt.trueBranch.accept(this, v);

        if (stmt.falseBranch.isEmpty()) {
            emitIfThen(stmt.cond.val, trueBranch, mv);
        } else {
            Consumer<FuncVisitor> falseBranch = v -> stmt.falseBranch.get().accept(this, v);
            emitIfThenElse(stmt.cond.val, trueBranch, falseBranch, mv);
        }
    }

    @Override
    default void visitWhile(Tree.While loop, FuncVisitor mv) {
        var exit = mv.freshLabel();
        Function<FuncVisitor, Temp> test = v -> {
            loop.cond.accept(this, v);
            return loop.cond.val;
        };
        Consumer<FuncVisitor> body = v -> {
            loopExits.push(exit);
            loop.body.accept(this, v);
            loopExits.pop();
        };
        emitWhile(test, body, exit, mv);
    }

    @Override
    default void visitFor(Tree.For loop, FuncVisitor mv) {
        var exit = mv.freshLabel();
        loop.init.accept(this, mv);
        Function<FuncVisitor, Temp> test = v -> {
            loop.cond.accept(this, v);
            return loop.cond.val;
        };
        Consumer<FuncVisitor> body = v -> {
            loopExits.push(exit);
            loop.body.accept(this, v);
            loopExits.pop();
            loop.update.accept(this, v);
        };
        emitWhile(test, body, exit, mv);
    }

    @Override
    default void visitBreak(Tree.Break stmt, FuncVisitor mv) {
        mv.visitBranch(loopExits.peek());
    }

    @Override
    default void visitReturn(Tree.Return stmt, FuncVisitor mv) {
        if (stmt.expr.isEmpty()) {
            mv.visitReturn();
        } else {
            var expr = stmt.expr.get();
            expr.accept(this, mv);
            mv.visitReturn(expr.val);
        }
    }

    @Override
    default void visitPrint(Tree.Print stmt, FuncVisitor mv) {
        for (var expr : stmt.exprs) {
            expr.accept(this, mv);
            if (expr.type.eq(BuiltInType.INT)) {
                mv.visitIntrinsicCall(Intrinsic.PRINT_INT, expr.val);
            } else if (expr.type.eq(BuiltInType.BOOL)) {
                mv.visitIntrinsicCall(Intrinsic.PRINT_BOOL, expr.val);
            } else if (expr.type.eq(BuiltInType.STRING)) {
                mv.visitIntrinsicCall(Intrinsic.PRINT_STRING, expr.val);
            }
        }
    }

    // Expressions

    @Override
    default void visitIntLit(Tree.IntLit expr, FuncVisitor mv) {
        expr.val = mv.visitLoad(expr.value);
    }

    @Override
    default void visitBoolLit(Tree.BoolLit expr, FuncVisitor mv) {
        expr.val = mv.visitLoad(expr.value);
    }

    @Override
    default void visitStringLit(Tree.StringLit expr, FuncVisitor mv) {
        // Remember to unquote the string literal
        var unquoted = expr.value.substring(1, expr.value.length() - 1)
                .replaceAll("\\\\r", "\r")
                .replaceAll("\\\\n", "\n")
                .replaceAll("\\\\t", "\t")
                .replaceAll("\\\\\\\\", "\\")
                .replaceAll("\\\\\"", "\"");
        expr.val = mv.visitLoad(unquoted);
    }

    @Override
    default void visitNullLit(Tree.NullLit expr, FuncVisitor mv) {
        expr.val = mv.visitLoad(0);
    }

    @Override
    default void visitReadInt(Tree.ReadInt expr, FuncVisitor mv) {
        expr.val = mv.visitIntrinsicCall(Intrinsic.READ_INT, true);
    }

    @Override
    default void visitReadLine(Tree.ReadLine expr, FuncVisitor mv) {
        expr.val = mv.visitIntrinsicCall(Intrinsic.READ_LINE, true);
    }

    @Override
    default void visitUnary(Tree.Unary expr, FuncVisitor mv) {
        var op = switch (expr.op) {
            case NEG -> TacInstr.Unary.Op.NEG;
            case NOT -> TacInstr.Unary.Op.LNOT;
        };

        expr.operand.accept(this, mv);
        expr.val = mv.visitUnary(op, expr.operand.val);
    }

    @Override
    default void visitBinary(Tree.Binary expr, FuncVisitor mv) {
        if ((expr.op.equals(Tree.BinaryOp.EQ) || expr.op.equals(Tree.BinaryOp.NE)) &&
                expr.lhs.type.eq(BuiltInType.STRING)) { // string eq/ne
            expr.lhs.accept(this, mv);
            expr.rhs.accept(this, mv);
            expr.val = mv.visitIntrinsicCall(Intrinsic.STRING_EQUAL, true, expr.lhs.val, expr.rhs.val);
            if (expr.op.equals(Tree.BinaryOp.NE)) {
                mv.visitUnarySelf(TacInstr.Unary.Op.LNOT, expr.val);
            }
            return;
        }

        var op = switch (expr.op) {
            case ADD -> TacInstr.Binary.Op.ADD;
            case SUB -> TacInstr.Binary.Op.SUB;
            case MUL -> TacInstr.Binary.Op.MUL;
            case DIV -> TacInstr.Binary.Op.DIV;
            case MOD -> TacInstr.Binary.Op.MOD;
            case EQ -> TacInstr.Binary.Op.EQU;
            case NE -> TacInstr.Binary.Op.NEQ;
            case LT -> TacInstr.Binary.Op.LES;
            case LE -> TacInstr.Binary.Op.LEQ;
            case GT -> TacInstr.Binary.Op.GTR;
            case GE -> TacInstr.Binary.Op.GEQ;
            case AND -> TacInstr.Binary.Op.LAND;
            case OR -> TacInstr.Binary.Op.LOR;
        };
        expr.lhs.accept(this, mv);
        expr.rhs.accept(this, mv);
        expr.val = mv.visitBinary(op, expr.lhs.val, expr.rhs.val);
    }

    @Override
    default void visitVarSel(Tree.VarSel expr, FuncVisitor mv) {
    	if (expr.methodSymbol != null) { // refer to a method
    		/*if (expr.isCaller) { // if it appears in a caller, no need to build a vtable
	    		if (!expr.methodSymbol.isStatic()) {
	    			expr.receiver.get().accept(this, mv);
	    			expr.val = expr.receiver.get().val;
    			} else {
    				//expr.val = ;
    			}
    			return;
    		}*/
    		var pw = getWriter();
    		var method = expr.methodSymbol;
    		var clazz = method.owner;
    		String id = clazz.name + "." + method.name; 
    		pw.buildVTableForMethod(id);
        	var vtbl = mv.visitLoadVTable(id);
        	// VTable pointer & 'this' pointer(for non-statics)
            Temp addr;
            if (!expr.methodSymbol.isStatic()) {
            	addr = mv.visitIntrinsicCall(Intrinsic.ALLOCATE, true, mv.visitLoad(12));
            	mv.visitStoreTo(addr, vtbl);
            	expr.receiver.get().accept(this, mv);
            	var object = expr.receiver.get().val;
            	mv.visitStoreTo(addr, 4, mv.visitLoad(1));
            	mv.visitStoreTo(addr, 8, object);
            } else {
            	addr = mv.visitIntrinsicCall(Intrinsic.ALLOCATE, true, mv.visitLoad(8));
            	mv.visitStoreTo(addr, vtbl);
            	mv.visitStoreTo(addr, 4, mv.visitLoad(0));
            }
            expr.val = addr;
     		return;
    	}
        if (expr.symbol.isMemberVar()) {
            var object = expr.receiver.get();
            object.accept(this, mv);
            expr.val = mv.visitMemberAccess(object.val, expr.symbol.getOwner().name, expr.name);
        } else { // local or param
            expr.val = expr.symbol.temp;
            if (expr.val.getDomain() != mv) {
            	expr.val = passCaptured(expr.val);
            }
        }
    }

    @Override
    default void visitIndexSel(Tree.IndexSel expr, FuncVisitor mv) {
        expr.array.accept(this, mv);
        expr.index.accept(this, mv);
        if (expr.array.val.getDomain() != mv)
        	expr.array.val = passCaptured(expr.array.val);
        var addr = emitArrayElementAddress(expr.array.val, expr.index.val, mv);
        expr.val = mv.visitLoadFrom(addr);
    }

    @Override
    default void visitNewArray(Tree.NewArray expr, FuncVisitor mv) {
        expr.length.accept(this, mv);
        expr.val = emitArrayInit(expr.length.val, mv);
    }

    @Override
    default void visitNewClass(Tree.NewClass expr, FuncVisitor mv) {
        expr.val = mv.visitNewClass(expr.symbol.name);
    }

    @Override
    default void visitThis(Tree.This expr, FuncVisitor mv) {
        expr.val = mv.getArgTemp(mv.argCnt()-1);
    }

    @Override
    default void visitCall(Tree.Call expr, FuncVisitor mv) {
        if (expr.isArrayLength) { // special case for array.length()

        	var arrayLen = (Tree.VarSel)expr.caller;
            var array = arrayLen.receiver.get();
            array.accept(this, mv);
            expr.val = mv.visitLoadFrom(array.val, -4);
            
            return;
        }
        
        var caller = expr.caller;
        if (caller instanceof Tree.VarSel) {
        	((Tree.VarSel)caller).isCaller = true;
        }
        caller.accept(this, mv);
        
        expr.args.forEach(arg -> arg.accept(this, mv));
        var temps = new ArrayList<Temp>();
        expr.args.forEach(arg -> temps.add(arg.val));
        
        if (caller.type.isFuncType()) {
        	boolean needReturn = !((FunType)caller.type).returnType.isVoidType();
        	var object = caller.val;
        	var vtbl = mv.visitLoadFrom(object);
        	var loadCnt = mv.visitLoadFrom(object, 4);
        	var func = mv.visitLoadFrom(vtbl, 8);

        	temps.forEach(t -> mv.visitRaw(new TacInstr.Parm(t)));
        	
        	Temp counter = mv.visitLoad(0);
        	Temp pos = mv.visitBinary(TacInstr.Binary.Op.ADD, object, mv.visitLoad(8));
        	Temp const_4 = mv.visitLoad(4);

        	var exit = mv.freshLabel();
        	Function<FuncVisitor, Temp> test = v -> {
                return mv.visitBinary(TacInstr.Binary.Op.LES, counter, loadCnt);
            };
        	Consumer<FuncVisitor> body = v -> {
        		mv.visitRaw(new TacInstr.Parm(mv.visitLoadFrom(pos)));
        		mv.visitAssign(pos, mv.visitBinary(TacInstr.Binary.Op.ADD, pos, const_4));
                mv.visitAssign(counter, mv.visitBinary(TacInstr.Binary.Op.ADD, counter, mv.visitLoad(1)));
        	};
        	emitWhile(test, body, exit, mv);
        	
        	expr.val = mv.visitStoredFunc(func, needReturn);
        	return;
        }
        
        assert false;
    }

    @Override
    default void visitClassTest(Tree.ClassTest expr, FuncVisitor mv) {
        // Accelerate: when obj.type <: class.type, then the test must be successful!
        if (expr.obj.type.subtypeOf(expr.symbol.type)) {
            expr.val = mv.visitLoad(1);
            return;
        }

        expr.obj.accept(this, mv);
        expr.val = emitClassTest(expr.obj.val, expr.symbol.name, mv);
    }

    @Override
    default void visitClassCast(Tree.ClassCast expr, FuncVisitor mv) {
        expr.obj.accept(this, mv);
        expr.val = expr.obj.val;

        // Accelerate: when obj.type <: class.type, then the test must success!
        if (expr.obj.type.subtypeOf(expr.symbol.type)) {
            return;
        }
        var result = emitClassTest(expr.obj.val, expr.symbol.name, mv);

        /* Pseudo code:
         * <pre>
         *     if (result != 0) branch exit  // cast success
         *     print "Decaf runtime error: " // RuntimeError.CLASS_CAST_ERROR1
         *     vtbl1 = *obj                  // vtable of obj
         *     fromClass = *(vtbl1 + 4)      // name of obj's class
         *     print fromClass
         *     print " cannot be cast to "   // RuntimeError.CLASS_CAST_ERROR2
         *     vtbl2 = load vtbl of the target class
         *     toClass = *(vtbl2 + 4)        // name of target class
         *     print toClass
         *     print "\n"                    // RuntimeError.CLASS_CAST_ERROR3
         *     halt
         * exit:
         * </pre>
         */
        var exit = mv.freshLabel();
        mv.visitBranch(TacInstr.CondBranch.Op.BNEZ, result, exit);
        mv.visitPrint(RuntimeError.CLASS_CAST_ERROR1);
        var vtbl1 = mv.visitLoadFrom(expr.obj.val);
        var fromClass = mv.visitLoadFrom(vtbl1, 4);
        mv.visitIntrinsicCall(Intrinsic.PRINT_STRING, fromClass);
        mv.visitPrint(RuntimeError.CLASS_CAST_ERROR2);
        var vtbl2 = mv.visitLoadVTable(expr.symbol.name);
        var toClass = mv.visitLoadFrom(vtbl2, 4);
        mv.visitIntrinsicCall(Intrinsic.PRINT_STRING, toClass);
        mv.visitPrint(RuntimeError.CLASS_CAST_ERROR3);
        mv.visitIntrinsicCall(Intrinsic.HALT);
        mv.visitLabel(exit);
    }

    /**
     * Emit code for the following conditional statement:
     * <pre>
     *     if (cond) {
     *         action
     *     }
     * </pre>
     * <p>
     * Implementation in pseudo code:
     * <pre>
     *     if (cond == 0) branch skip;
     *     action
     * skip:
     * </pre>
     * <p>
     * Why {@link Consumer} for the true branch? Because the method visitor will append TAC code <em>in order</em>.
     * Since the instructions of the true branch go AFTER the conditional branch instruction, we must first append the
     * conditional branch, and then the true branch. So instead of appending the code first, which is wrong, we must
     * wrap the <em>process</em> which emits the actual code as a function {@link FuncVisitor} {@literal ->} void,
     * expressed by {@link Consumer} in Java. Same story for the helper methods below.
     *
     * @param cond   temp of condition
     * @param action code (to be generated) of the true branch
     * @param mv     current method visitor
     */
    private void emitIfThen(Temp cond, Consumer<FuncVisitor> action, FuncVisitor mv) {
        var skip = mv.freshLabel();
        mv.visitBranch(TacInstr.CondBranch.Op.BEQZ, cond, skip);
        action.accept(mv);
        mv.visitLabel(skip);
    }

    /**
     * Emit code for the following conditional statement:
     * <pre>
     *     if (cond) {
     *         trueBranch
     *     } else {
     *         falseBranch
     *     }
     * </pre>
     * <p>
     * Implementation in pseudo code:
     * <pre>
     *     if (cond == 0) branch skip
     *     trueBranch
     *     branch exit
     * skip:
     *     falseBranch
     * exit:
     * </pre>
     *
     * @param cond        temp of condition
     * @param trueBranch  code (to be generated) of the true branch
     * @param falseBranch code (to be generated) of the false branch
     * @param mv          current method visitor
     */
    private void emitIfThenElse(Temp cond, Consumer<FuncVisitor> trueBranch, Consumer<FuncVisitor> falseBranch,
                                FuncVisitor mv) {
        var skip = mv.freshLabel();
        var exit = mv.freshLabel();
        mv.visitBranch(TacInstr.CondBranch.Op.BEQZ, cond, skip);
        trueBranch.accept(mv);
        mv.visitBranch(exit);
        mv.visitLabel(skip);
        falseBranch.accept(mv);
        mv.visitLabel(exit);
    }

    /**
     * Emit code for the following loop:
     * <pre>
     *     while (cond) {
     *         block
     *     }
     * </pre>
     * <p>
     * Implementation in pseudo code:
     * <pre>
     * entry:
     *     cond = do test
     *     if (cond == 0) branch exit
     *     do block
     *     branch entry
     * exit:
     * </pre>
     *
     * @param test  code (to be generated) of the loop condition
     * @param block code (to be generated) of the loop body
     * @param exit  label of loop exit
     * @param mv    current method visitor
     */
    private void emitWhile(Function<FuncVisitor, Temp> test, Consumer<FuncVisitor> block,
                           Label exit, FuncVisitor mv) {
        var entry = mv.freshLabel();
        mv.visitLabel(entry);
        var cond = test.apply(mv);
        mv.visitBranch(TacInstr.CondBranch.Op.BEQZ, cond, exit);
        block.accept(mv);
        mv.visitBranch(entry);
        mv.visitLabel(exit);
    }

    /**
     * Emit code for initializing a new array.
     * <p>
     * In memory, an array of length {@code n} takes {@code (n + 1) * 4} bytes:
     * - the first 4 bytes: length
     * - the rest bytes: data
     * <p>
     * Pseudo code:
     * <pre>
     *     error = length {@literal <} 0
     *     if (error) {
     *         throw RuntimeError.NEGATIVE_ARR_SIZE
     *     }
     *
     *     units = length + 1
     *     size = units * 4
     *     a = ALLOCATE(size)
     *     *(a + 0) = length
     *     p = a + size
     *     p -= 4
     *     while (p != a) {
     *         *(p + 0) = 0
     *         p -= 4
     *     }
     *     ret = (a + 4)
     * </pre>
     *
     * @param length temp of array length
     * @param mv     current method visitor
     * @return a temp storing the address of the first element of the array
     */
    private Temp emitArrayInit(Temp length, FuncVisitor mv) {
        var zero = mv.visitLoad(0);
        var error = mv.visitBinary(TacInstr.Binary.Op.LES, length, zero);
        var handler = new Consumer<FuncVisitor>() {
            @Override
            public void accept(FuncVisitor v) {
                v.visitPrint(RuntimeError.NEGATIVE_ARR_SIZE);
                v.visitIntrinsicCall(Intrinsic.HALT);
            }
        };
        emitIfThen(error, handler, mv);

        var units = mv.visitBinary(TacInstr.Binary.Op.ADD, length, mv.visitLoad(1));
        var four = mv.visitLoad(4);
        var size = mv.visitBinary(TacInstr.Binary.Op.MUL, units, four);
        var a = mv.visitIntrinsicCall(Intrinsic.ALLOCATE, true, size);
        mv.visitStoreTo(a, length);
        var p = mv.visitBinary(TacInstr.Binary.Op.ADD, a, size);
        mv.visitBinarySelf(TacInstr.Binary.Op.SUB, p, four);
        Function<FuncVisitor, Temp> test = v -> v.visitBinary(TacInstr.Binary.Op.NEQ, p, a);
        var body = new Consumer<FuncVisitor>() {
            @Override
            public void accept(FuncVisitor v) {
                v.visitStoreTo(p, zero);
                v.visitBinarySelf(TacInstr.Binary.Op.SUB, p, four);
            }
        };
        emitWhile(test, body, mv.freshLabel(), mv);
        return mv.visitBinary(TacInstr.Binary.Op.ADD, a, four);
    }

    /**
     * Emit code for computing the address of an array element.
     * <p>
     * Pseudo code:
     * <pre>
     *     length = *(array - 4)
     *     error1 = index {@literal <} 0
     *     error2 = index {@literal >=} length
     *     error = error1 || error2
     *     if (error) {
     *         throw RuntimeError.ARRAY_INDEX_OUT_OF_BOUND
     *     }
     *
     *     offset = index * 4
     *     ret = array + offset
     * </pre>
     *
     * @param array temp of the array
     * @param index temp of the index
     * @return a temp storing the address of the element
     */
    private Temp emitArrayElementAddress(Temp array, Temp index, FuncVisitor mv) {
        var length = mv.visitLoadFrom(array, -4);
        var zero = mv.visitLoad(0);
        var error1 = mv.visitBinary(TacInstr.Binary.Op.LES, index, zero);
        var error2 = mv.visitBinary(TacInstr.Binary.Op.GEQ, index, length);
        var error = mv.visitBinary(TacInstr.Binary.Op.LOR, error1, error2);
        var handler = new Consumer<FuncVisitor>() {
            @Override
            public void accept(FuncVisitor v) {
                v.visitPrint(RuntimeError.ARRAY_INDEX_OUT_OF_BOUND);
                v.visitIntrinsicCall(Intrinsic.HALT);
            }
        };
        emitIfThen(error, handler, mv);

        var four = mv.visitLoad(4);
        var offset = mv.visitBinary(TacInstr.Binary.Op.MUL, index, four);
        return mv.visitBinary(TacInstr.Binary.Op.ADD, array, offset);
    }

    /**
     * Emit code for testing if an object is an instance of class.
     * <p>
     * Pseudo code:
     * <pre>
     *     target = LoadVtbl(clazz)
     *     t = *object
     * loop:
     *     ret = t == target
     *     if (ret != 0) goto exit
     *     t = *t
     *     if (t != 0) goto loop
     *     ret = 0 // t == null
     * exit:
     * </pre>
     *
     * @param object temp of the object/instance
     * @param clazz  name of the class
     * @return a temp storing the result (1 for true, and 0 for false)
     */
    private Temp emitClassTest(Temp object, String clazz, FuncVisitor mv) {
        var target = mv.visitLoadVTable(clazz);
        var t = mv.visitLoadFrom(object);

        var loop = mv.freshLabel();
        var exit = mv.freshLabel();
        mv.visitLabel(loop);
        var ret = mv.visitBinary(TacInstr.Binary.Op.EQU, t, target);
        mv.visitBranch(TacInstr.CondBranch.Op.BNEZ, ret, exit);
        mv.visitRaw(new TacInstr.Memory(TacInstr.Memory.Op.LOAD, t, t, 0));
        mv.visitBranch(TacInstr.CondBranch.Op.BNEZ, t, loop);
        var zero = mv.visitLoad(0);
        mv.visitAssign(ret, zero);
        mv.visitLabel(exit);

        return ret;
    }
}
