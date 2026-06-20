package shit.nilore.modules.impl.misc.ai.btree;

import shit.nilore.modules.impl.misc.ai.Blackboard;

public abstract class BTNode {

    public enum Status {
        SUCCESS, FAILURE, RUNNING
    }

    protected Status status = Status.FAILURE;

    public abstract Status tick(Blackboard bb);

    public void reset() {
        status = Status.FAILURE;
    }
}
