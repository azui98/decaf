package decaf.lowlevel.tac;

import decaf.lowlevel.instr.Temp;
import decaf.lowlevel.label.FuncLabel;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class TacFunc implements Comparable<TacFunc> {
    public final FuncLabel entry;

    public final int numArgs;

    TacFunc(FuncLabel entry, int numArgs) {
        this.entry = entry;
        this.numArgs = numArgs;
    }
    
    TacFunc mapTemps(Map<Temp, Temp> map) {
    	TacFunc ret = new TacFunc(this.entry, this.numArgs);
    	this.instrSeq.forEach(instr -> ret.add(instr.map(map)));
    	ret.tempUsed = this.tempUsed;
    	return ret;
    }

    public List<TacInstr> getInstrSeq() {
        return instrSeq;
    }

    public int getUsedTempCount() {
        return tempUsed;
    }

    List<TacInstr> instrSeq = new ArrayList<>();

    int tempUsed;

    void add(TacInstr instr) {
        instrSeq.add(instr);
    }

    public void printTo(PrintWriter pw) {
        for (var instr : instrSeq) {
            if (instr.isLabel()) {
                pw.println(instr);
            } else {
                pw.println("    " + instr);
            }
        }
        pw.println();
    }

    @Override
    public int compareTo(TacFunc that) {
        return this.entry.name.compareTo(that.entry.name);
    }
}
