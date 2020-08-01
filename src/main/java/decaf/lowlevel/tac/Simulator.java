package decaf.lowlevel.tac;

import decaf.lowlevel.instr.Temp;
import decaf.lowlevel.label.FuncLabel;
import decaf.lowlevel.label.IntrinsicLabel;
import decaf.lowlevel.label.Label;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TAC program simulator.
 */
public final class Simulator {

    /**
     * Constructor.
     *
     * @param in  simulator's stdin
     * @param out simulator's stdout
     */
    public Simulator(InputStream in, OutputStream out) {
        _in = in;
        _out = new PrintWriter(out);
    }

    /**
     * Execute a TAC program.
     *
     * @param program TAC program
     */
    public void execute(TacProg program) {
        // acquire the first coroutine
        CoroutineContext ctx = new CoroutineContext();

        // Initialize
        _memory = new Memory();
        _string_pool = new StringPool();
        _vtable_to_addr = new TreeMap<>();
        _instrs = new Vector<>();
        _label_to_addr = new TreeMap<>();
        _addr_to_function = new TreeMap<>();
        _label_to_function = new TreeMap<>();

        // Allocate vtables
        for (VTable vtbl : program.vtables) {
            var addr = _memory.alloc(vtbl.getSize());
            _vtable_to_addr.put(vtbl.label.name, addr);
        }

        // Load instructions
        var addr = 0;
        for (var func : program.funcs) {
            // Meet a function: label -> func, label -> addr, addr -> func
            _label_to_function.put(func.entry.name, func);
            _addr_to_function.put(addr, func);

            // Add every non-pseudo instruction, and record labels if necessary
            for (var instr : func.getInstrSeq()) {
                if (instr.isLabel()) { // meet a label
                    var lbl = instr.label;
                    _label_to_addr.put(lbl.name, addr);
                } else {
                    _instrs.add(instr);
                    addr++;
                } // else: memo, ignore
            }

            // Check if the last instruction is RETURN
            if (!_instrs.lastElement().isReturn()) {
                throw new Error(String.format("In TAC function %s: the last instruction must be return",
                        func.entry.prettyString()));
            }
        }

        // Fill in vtables
        for (VTable vtbl : program.vtables) {
            addr = _vtable_to_addr.get(vtbl.label.name);
            var offset = 0;

            var parentAddr = vtbl.parent.map(pv -> _vtable_to_addr.get(pv.label.name)).orElse(0);
            _memory.store(parentAddr, addr, offset);
            offset += 4;

            var className = _string_pool.add(vtbl.className);
            _memory.store(className, addr, 4);
            offset += 4;

            for (var item : vtbl.getItems()) {
                _memory.store(_label_to_addr.get(item.name), addr, offset);
                offset += 4;
            }
        }

        // Initialize call stack and push the frame of main function
        if (!_label_to_function.containsKey(FuncLabel.MAIN_LABEL.name)) {
            throw new Error("No legal main function found");
        }

        var frame = new Frame(_label_to_function.get(FuncLabel.MAIN_LABEL.name), ctx);
        ctx._call_stack.push(frame);
        ctx._pc = _label_to_addr.get(FuncLabel.MAIN_LABEL.name);

        var mainCoroutine = new Coroutine(ctx);

        _halt = false;

        scheduler.addCoroutine(mainCoroutine);
        scheduler.start();
    }

    /**
     * IO.
     */
    private final InputStream _in;
    private final PrintWriter _out;

    /**
     * Memory.
     */
    private Memory _memory;

    /**
     * String pool: either constant or read from stdin.
     */
    private StringPool _string_pool;

    /**
     * Look up a vtable's address in memory by its name.
     */
    private Map<String, Integer> _vtable_to_addr;

    /**
     * Simulate instruction memory. The "address" is simply the index of this vector.
     */
    private Vector<TacInstr> _instrs;

    /**
     * Look up a label's address in instruction memory by its name.
     */
    private Map<String, Integer> _label_to_addr;

    /**
     * Look up a function by its entry label.
     */
    private Map<String, TacFunc> _label_to_function;

    /**
     * Look up a function by the address of its entry instruction.
     */
    private Map<Integer, TacFunc> _addr_to_function;

    /**
     * Call stack, consists of frames.
     */
    //private Stack<Frame> _call_stack;

    /**
     * Temporarily save the actual arguments given by the PARM instruction. These will be erased once a new stack
     * is created.
     */
    //private Vector<Integer> _actual_args;

    /**
     * Program counter: point to the address of the instruction being executed.
     */
    //private int _pc;

    /**
     * Halt signal.
     */
    private boolean _halt;

    /**
     * Stack frame.
     */
    private class Frame {
        /**
         * Host context.
         */
        final CoroutineContext ctx;

        /**
         * The function entry.
         */
        final Label entry;

        /**
         * An array to store values of local temps.
         */
        int[] array;

        /**
         * Save: which temp to write the return value.
         */
        Temp retValDst;

        /**
         * Save: the address of the next instruction to be executed once the function call returns.
         */
        int pcNext;

        Frame(Label entry, int arraySize, CoroutineContext ctx) {
            this.ctx = ctx;
            this.entry = entry;
            this.array = new int[arraySize];
            var i = 0;
            for (var arg : ctx._actual_args) { // copy actual arguments
                this.array[i] = arg;
                i++;
            }
            ctx._actual_args.clear(); // it will save args for future calls
        }

        Frame(TacFunc func, CoroutineContext ctx) {
            this(func.entry, func.getUsedTempCount(), ctx);
            //System.out.println(func.entry + " " + func.getUsedTempCount());
        }

        Frame(Frame t, CoroutineContext ctx) {
            this.ctx = ctx;
            this.array = t.array.clone();
            this.entry = t.entry;
            this.pcNext = t.pcNext;
            this.retValDst = t.retValDst;
        }
    }

    static int coroutineID;

    /**
     * Instruction executor.
     */
    private class Coroutine  {

        final CoroutineContext ctx;
        final InstrExecutor executor;
        public final int coroutineID;
        private boolean locked;

        Coroutine(CoroutineContext ctx) {
            this.ctx = ctx;
            this.coroutineID = ++Simulator.coroutineID;
            executor = new InstrExecutor();
        }

        /**
         * @return number of instructions executed
         */
        void run(AtomicBoolean signal) {
            int cnt = 0;
            locked = false;

            while (!ctx._call_stack.isEmpty()) {
                if (_halt) break;
                if (signal.get() && cnt>5) {
                    signal.set(false);
                    break;
                }

                //System.out.println("executing : " + _instrs.get(_pc));
                _instrs.get(ctx._pc).accept(executor);
                cnt++;

                if (locked) break;
            }
        }

        boolean finished() {
            return ctx._call_stack.isEmpty();
        }

        private class InstrExecutor implements TacInstr.Visitor {
            @Override
            public void visitAssign(TacInstr.Assign instr) {
                var frame = ctx._call_stack.peek();
                frame.array[instr.dst.index] = frame.array[instr.src.index];

                ctx._pc++;
            }

            @Override
            public void visitLoadVTbl(TacInstr.LoadVTbl instr) {
                var frame = ctx._call_stack.peek();
                frame.array[instr.dst.index] = _vtable_to_addr.get(instr.vtbl.label.name);

                ctx._pc++;
            }

            @Override
            public void visitLoadImm4(TacInstr.LoadImm4 instr) {
                var frame = ctx._call_stack.peek();
                frame.array[instr.dst.index] = instr.value;

                ctx._pc++;
            }

            @Override
            public void visitLoadStrConst(TacInstr.LoadStrConst instr) {
                var frame = ctx._call_stack.peek();
                var index = _string_pool.add(instr.value);
                frame.array[instr.dst.index] = index;

                ctx._pc++;
            }

            @Override
            public void visitUnary(TacInstr.Unary instr) {
                var frame = ctx._call_stack.peek();
                int operand = frame.array[instr.operand.index];
                frame.array[instr.dst.index] = switch (instr.op) {
                    case NEG -> -operand;
                    case LNOT -> (operand == 0) ? 1 : 0;
                };

                ctx._pc++;
            }

            @Override
            public void visitBinary(TacInstr.Binary instr) {
                var frame = ctx._call_stack.peek();
                var lhs = frame.array[instr.lhs.index];
                var rhs = frame.array[instr.rhs.index];
                frame.array[instr.dst.index] = switch (instr.op) {
                    case ADD -> lhs + rhs;
                    case SUB -> lhs - rhs;
                    case MUL -> lhs * rhs;
                    case DIV -> lhs / rhs;
                    case MOD -> lhs % rhs;
                    case EQU -> (lhs == rhs) ? 1 : 0;
                    case NEQ -> (lhs != rhs) ? 1 : 0;
                    case LES -> (lhs < rhs) ? 1 : 0;
                    case LEQ -> (lhs <= rhs) ? 1 : 0;
                    case GTR -> (lhs > rhs) ? 1 : 0;
                    case GEQ -> (lhs >= rhs) ? 1 : 0;
                    case LAND -> (lhs == 0) ? 0 : (rhs == 0) ? 0 : 1;
                    case LOR -> (lhs != 0) ? 1 : (rhs == 0) ? 0 : 1;
                };

                ctx._pc++;
            }

            @Override
            public void visitBranch(TacInstr.Branch instr) {
                ctx._pc = _label_to_addr.get(instr.target.name);
            }

            @Override
            public void visitCondBranch(TacInstr.CondBranch instr) {
                var frame = ctx._call_stack.peek();
                var jump = switch (instr.op) {
                    case BEQZ -> frame.array[instr.cond.index] == 0;
                    case BNEZ -> frame.array[instr.cond.index] != 0;
                };

                if (jump) {
                    ctx._pc = _label_to_addr.get(instr.target.name);
                } else {
                    ctx._pc++;
                }
            }

            @Override
            public void visitReturn(TacInstr.Return instr) {
                var value = instr.value.map(temp -> ctx._call_stack.peek().array[temp.index]);
                returnWith(value);
            }

            private void returnWith(Optional<Integer> value) {
                // Destroy the callee's frame
                ctx._call_stack.pop();

                // Recover caller's state, if the caller exists
                if (!ctx._call_stack.isEmpty()) {
                    var frame = ctx._call_stack.peek();
                    value.ifPresent(v -> frame.array[frame.retValDst.index] = v);
                    ctx._pc = ctx._call_stack.peek().pcNext;
                } // else: the entire program terminates
            }

            @Override
            public void visitParm(TacInstr.Parm instr) {
                var frame = ctx._call_stack.peek();
                ctx._actual_args.add(frame.array[instr.value.index]);

                //System.out.println("--------------------" + _actual_args);

                ctx._pc++;
            }

            @Override
            public void visitIndirectCall(TacInstr.IndirectCall instr) {
                // Save caller's state
                var frame = ctx._call_stack.peek();
                frame.pcNext = ctx._pc + 1;
                frame.retValDst = instr.dst.orElse(null);

                // Create callee's frame and invoke
                var addr = frame.array[instr.entry.index];
                var func = _addr_to_function.get(addr);
                if (!ctx.isNextCallAsync) {
                    ctx._call_stack.push(new Frame(func, ctx));
                    ctx._pc = addr;
                } else {
                    ctx.isNextCallAsync = false;
                    CoroutineContext ctx_ = new CoroutineContext();
                    ctx_._call_stack.push(new Frame(func, ctx));
                    ctx_._pc = addr;
                    scheduler.addCoroutine(new Coroutine(ctx_));
                    ctx._pc++;
                }
            }

            @Override
            public void visitDirectCall(TacInstr.DirectCall instr) {
                // Save caller's state
                var frame = ctx._call_stack.peek();
                frame.pcNext = ctx._pc + 1;
                frame.retValDst = instr.dst.orElse(null);

                // Create callee's frame and invoke
                if (instr.entry.isIntrinsic()) { // special: call intrinsic
                    var il = (IntrinsicLabel) instr.entry;
                    ctx._call_stack.push(new Frame(il, 2, ctx));
                    callIntrinsic(il.opcode);
                } else {
                    var func = _label_to_function.get(instr.entry.name);
                    if (!ctx.isNextCallAsync) {
                        ctx._call_stack.push(new Frame(func, ctx));
                        ctx._pc = _label_to_addr.get(instr.entry.name);
                    } else {
                        ctx.isNextCallAsync = false;
                        CoroutineContext ctx_ = new CoroutineContext();
                        ctx_._call_stack.push(new Frame(func, ctx_));
                        ctx_._pc = _label_to_addr.get(instr.entry.name);
                        ctx._pc++;
                        scheduler.addCoroutine(new Coroutine(ctx_));
                    }
                }
            }

            @Override
            public void visitGoLabel(TacInstr.GoLabel instr) {
                ctx.isNextCallAsync = true;
                ctx._pc++;
            }

            @Override
            public void visitLock(TacInstr.Lock instr) {
                Frame frame = ctx._call_stack.peek();
                int lockID = frame.array[instr.lockID.index];
                boolean f1 = lockState.containsKey(lockID);
                boolean f2 = false;
                if (f1) f2 = lockState.get(lockID);
                if (lockState.containsKey(lockID) && lockState.get(lockID))
                    locked = true;
                else {
                    lockState.put(lockID, true);
                    ctx._pc++;
                }
            }

            @Override
            public void visitUnlock(TacInstr.Unlock instr) {
                Frame frame = ctx._call_stack.peek();
                int lockID = frame.array[instr.lockID.index];
                if ((!lockState.containsKey(lockID)) || (!lockState.get(lockID))) {
                    System.out.println("\nRuntime Error: lock_" + lockID + " is not locked !");
                    System.exit(-1);
                }
                lockState.put(lockID, false);
                ctx._pc++;
            }

            private void callIntrinsic(Intrinsic.Opcode opcode) {
                var frame = ctx._call_stack.peek();
                Optional<Integer> retVal = Optional.empty();

                switch (opcode) {
                    case ALLOCATE -> retVal = Optional.of(_memory.alloc(frame.array[0]));
                    case READ_LINE -> {
                        var scanner = new Scanner(_in);
                        var str = scanner.nextLine();
                        assert str.length() <= 63;
                        retVal = Optional.of(_string_pool.add(str));
                    }
                    case READ_INT -> {
                        var scanner = new Scanner(_in);
                        var value = scanner.nextInt();
                        retVal = Optional.of(value);
                    }
                    case STRING_EQUAL -> retVal = Optional.of(frame.array[0] == frame.array[1] ? 1 : 0);
                    case PRINT_INT -> {
                        _out.print(frame.array[0]);
                        _out.flush();
                    }
                    case PRINT_STRING -> {
                        _out.print(_string_pool.get(frame.array[0]));
                        _out.flush();
                    }
                    case PRINT_BOOL -> {
                        _out.print(frame.array[0] == 0 ? "false" : "true");
                        _out.flush();
                    }
                    case HALT -> _halt = true;
                }

                returnWith(retVal);
            }

            @Override
            public void visitMemory(TacInstr.Memory instr) {
                var frame = ctx._call_stack.peek();
                int base = frame.array[instr.base.index];
                //System.out.println(instr + " : " + base + " + " + instr.offset);
                int offset = instr.offset;
                switch (instr.op) {
                    case LOAD -> frame.array[instr.dst.index] = _memory.load(base, offset);
                    case STORE -> _memory.store(frame.array[instr.dst.index], base, offset);
                }

                ctx._pc++;
            }
        }

    }

    /**
     * Memory.
     */
    private class Memory {
        /**
         * Don't start from address 0, because 0 is reserved as the null pointer.
         */
        private int currentSize = 4;

        private class Block implements Comparable<Block> {

            public int start;

            public int[] mem;

            @Override
            public int compareTo(Block o) {
                return start > o.start ? 1 : start == o.start ? 0 : -1;
            }
        }

        /**
         * A heap is a list of memory blocks. It stores the actual data.
         */
        private List<Block> heap = new ArrayList<>();

        /**
         * Allocate memory in bytes.
         *
         * @param size the size in bytes.
         * @return the starting address of the allocated memory block
         */
        public int alloc(int size) {
            if (size < 0) {
                throw new Error("Memory allocation error: negative size " + size);
            }

            if (size % 4 != 0) {
                size += 4 - (size % 4);
            }

            size /= 4;
            Block block = new Block();
            block.start = currentSize;
            currentSize += size;
            block.mem = new int[size];
            heap.add(block);
            return block.start * 4;
        }

        private Block checkHeapAccess(int base, int offset) {
        	//System.out.println(base + " + " + offset);
        	
            if (base <= 0) {
                throw new Error("Null pointer exception");
            }

            if (base % 4 != 0) {
                throw new Error("Base address not aligned: " + base);
            }

            if (offset % 4 != 0) {
                throw new Error("Offset not aligned: " + base);
            }

            base /= 4;
            offset /= 4;
            if (base >= currentSize) {
                throw new Error(String.format("Memory access out of bound %d", base * 4));
            }
            Block temp = new Block();
            temp.start = base;
            int index = Collections.binarySearch(heap, temp);
            Block block = index >= 0 ? heap.get(index) : heap.get(-index - 2);
            int accessIndex = base - block.start + offset;
            if (accessIndex < 0 || accessIndex >= block.mem.length) {
                throw new Error(String.format("Memory access out of bound %d", base * 4 + offset * 4));
            }
            return block;
        }

        public int load(int base, int offset) {
            Block block = checkHeapAccess(base, offset);
            return block.mem[base / 4 - block.start + offset / 4];
        }

        public void store(int value, int base, int offset) {
            Block block = checkHeapAccess(base, offset);
            block.mem[base / 4 - block.start + offset / 4] = value;
        }
    }

    private static class Error extends RuntimeException {
        Error(String msg) {
            super("In simulator: " + msg);
        }
    }

    /**
     * Context of a coroutine, most importantly pc and stack
     * For native languages registers should also be restored,
     * however in TacVM pseudo registers are stored in stack frames.
     */
    private class CoroutineContext {
        // Program counter
        public int _pc;

        // Call stack
        private Stack<Frame> _call_stack = new Stack<>();

        public boolean isNextCallAsync;

        /**
         * Temporarily save the actual arguments given by the PARM instruction. These will be erased once a new stack
         * is created.
         */
        private Vector<Integer> _actual_args = new Vector<>();

        CoroutineContext() { }

        CoroutineContext fork() {
            CoroutineContext ctx = new CoroutineContext();
            ctx._pc = this._pc;
            ctx._actual_args.addAll(this._actual_args);
            this._call_stack.forEach(t -> ctx._call_stack.add(new Frame(t, ctx)));
            return ctx;
        }
    }

    class Scheduler {
        Queue<Coroutine> queue = new LinkedList<>();

        /**
         * After every 5ms ticked will be set true to wake and run a new coroutine
         */
        AtomicBoolean signal = new AtomicBoolean(false);

        Timer timer;

        Scheduler() {
            timer = new Timer("clock");
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    signal.set(true);
                }
            }, 0, 1);
        }

        void addCoroutine(Coroutine coroutine) {
            //System.out.println(coroutine.coroutineID);
            queue.add(coroutine);
        }

        void start() {
            // Round-Robin scheduler
            while (!queue.isEmpty()) {
                Coroutine task = queue.poll();
                //System.out.println("now running" + task.coroutineID);
                task.run(signal);
                if (!task.finished())
                    queue.offer(task);
            }
            timer.cancel();
        }

    }

    private Scheduler scheduler = new Scheduler();
    private Map<Integer, Boolean> lockState = new HashMap<>();
}
