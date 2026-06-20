package shit.nilore.modules.impl.misc.ai.btree;

import shit.nilore.modules.impl.misc.ai.Blackboard;

public class Inverter extends BTNode {

    private final BTNode child;

    public Inverter(BTNode child) {
        this.child = child;
    }

    @Override
    public Status tick(Blackboard bb) {
        Status s = child.tick(bb);
        if (s == Status.SUCCESS) return status = Status.FAILURE;
        if (s == Status.FAILURE) return status = Status.SUCCESS;
        return status = Status.RUNNING;
    }

    @Override
    public void reset() {
        super.reset();
        child.reset();
    }
}
