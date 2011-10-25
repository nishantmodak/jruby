package org.jruby.compiler.ir.dataflow;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.ListIterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jruby.compiler.ir.representations.CFG;
import org.jruby.compiler.ir.representations.CFG.CFG_Edge;
import org.jruby.compiler.ir.representations.BasicBlock;

public abstract class DataFlowProblem {
/* -------------- Public fields and methods below ---------------- */
    public enum DF_Direction { FORWARD, BACKWARD, BIDIRECTIONAL };

    public final DF_Direction direction;

    public DataFlowProblem(DF_Direction d) {
        direction = d;
        variables = new ArrayList<DataFlowVar>();
        nextVariableId = -1;
    }

// ------- Abstract methods without a default implementation -------
    abstract public FlowGraphNode buildFlowGraphNode(BasicBlock bb);
    abstract public String getName();

// ------- Default implementation methods below -------
    /** Are there are available data flow facts to run this problem? SSS FIXME: Silly optimization? */
    public boolean isEmpty() {
        return false;
    }

    public DF_Direction getFlowDirection() {
        return direction;
    }

    public void setup(CFG c) {
        cfg = c;
        buildFlowGraph();
    }

    public CFG getCFG() {
        return cfg;
    }

    /* Compute Meet Over All Paths solution for this dataflow problem on the input CFG.
     * This implements a standard worklist algorithm. */
    public void compute_MOP_Solution() {
        /** Are there are available data flow facts to run this problem? SSS FIXME: Silly optimization? */
        if (!isEmpty()) {
            for (FlowGraphNode fg: flowGraphNodes) {
                fg.init();
            }

            // 2. Initialize work list based on flow direction to make processing efficient!
            LinkedList<FlowGraphNode> workList = getInitialWorkList();

            // 3. Initialize a bitset with a flag set for all basic blocks
            int numNodes = cfg.getMaxNodeID();
            BitSet bbSet = new BitSet(1+numNodes);
            bbSet.flip(0, numNodes);

            // 4. Iteratively compute data flow info
            while (!workList.isEmpty()) {
                workList.removeFirst().computeDataFlowInfo(workList, bbSet);
            }
        }
    }

    private LinkedList<FlowGraphNode> getInitialWorkList() {
        LinkedList<FlowGraphNode> wl = new LinkedList<FlowGraphNode>();
        if (direction == DF_Direction.FORWARD) {
           ListIterator<BasicBlock> it = cfg.getReversePostOrderTraverser();
           while (it.hasPrevious()) {
              wl.add(getFlowGraphNode(it.previous()));
           }
        } else {
           ListIterator<BasicBlock> it = cfg.getPostOrderTraverser();
           while (it.hasNext()) {
              wl.add(getFlowGraphNode(it.next()));
           }
        }

        return wl;
    }

    public int getDFVarsCount() {
        return variables.size();
    }

    public Set<CFG_Edge> incomingEdgesOf(BasicBlock bb) {
        return cfg.incomingEdgesOf(bb);
    }

    public Set<CFG_Edge> outgoingEdgesOf(BasicBlock bb) {
        return cfg.outgoingEdgesOf(bb);
    }

    /* Individual analyses should override this */
    public String getDataFlowVarsForOutput() {
        return "";
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("----").append(getName()).append("----\n");
  
        buf.append("---- Data Flow Vars: ----\n");
        buf.append(getDataFlowVarsForOutput());
        buf.append("-------------------------\n");
  
        for (FlowGraphNode n: flowGraphNodes) {
            buf.append("DF State for BB ").append(n._bb.getID()).append(":\n").append(n.toString());
        }

        return buf.toString();
    }

/* -------------- Packaged/protected fields and methods below ---------------- */
    int addDataFlowVar(DataFlowVar v) {
        // We want unique ids for dataflow variables
        nextVariableId++;
        variables.add(nextVariableId, v);
        return nextVariableId;
    }

/* -------------- Protected fields and methods below ---------------- */
    protected CFG                    cfg;
    protected List<FlowGraphNode>    flowGraphNodes;

    protected FlowGraphNode getFlowGraphNode(BasicBlock b) {
        return basicBlockToFlowGraph.get(b.getID());
    }

/* -------------- Private fields and methods below ---------------- */
    private int     nextVariableId;
    private ArrayList<DataFlowVar> variables;
    private Map<Integer, FlowGraphNode> basicBlockToFlowGraph;

    private void buildFlowGraph() {
        flowGraphNodes = new LinkedList<FlowGraphNode>();
        basicBlockToFlowGraph = new HashMap<Integer, FlowGraphNode>();

        for (BasicBlock bb: cfg.getNodes()) {
            FlowGraphNode fgNode = buildFlowGraphNode(bb);
            fgNode.buildDataFlowVars();
            flowGraphNodes.add(fgNode);
            basicBlockToFlowGraph.put(bb.getID(), fgNode);
        }
    }
}
