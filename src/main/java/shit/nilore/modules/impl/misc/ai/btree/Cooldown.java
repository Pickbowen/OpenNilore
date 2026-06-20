package shit.nilore.modules.impl.misc.ai.btree;

import shit.nilore.modules.impl.misc.ai.Blackboard;

public class Cooldown extends BTNode {

    private final BTNode child;
    private final int cooldownTicks;
    private int lastRunTick = -1;

    public Cooldown(BTNode child, int cooldownTicks) {
        this.child = child;
        this.cooldownTicks = cooldownTicks;
    }

    @Override
    public Status tick(Blackboard bb) {
        if (bb.tickCount - lastRunTick < cooldownTicks) {
            return status = Status.FAILURE;
        }
        Status s = child.tick(bb);
        if (s == Status.SUCCESS) {
            lastRunTick = bb.tickCount;
        }
        return status = s;
    }

    @Override
    public void reset() {
        super.reset();
        child.reset();
    }
}
