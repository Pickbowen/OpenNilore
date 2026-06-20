package shit.nilore.modules.impl.misc.ai.btree;

import shit.nilore.modules.impl.misc.ai.Blackboard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Sequence extends BTNode {

    private final List<BTNode> children;

    public Sequence(BTNode... children) {
        this.children = new ArrayList<>(Arrays.asList(children));
    }

    @Override
    public Status tick(Blackboard bb) {
        for (BTNode child : children) {
            Status s = child.tick(bb);
            if (s == Status.FAILURE || s == Status.RUNNING) {
                return status = s;
            }
        }
        return status = Status.SUCCESS;
    }

    @Override
    public void reset() {
        super.reset();
        children.forEach(BTNode::reset);
    }
}
