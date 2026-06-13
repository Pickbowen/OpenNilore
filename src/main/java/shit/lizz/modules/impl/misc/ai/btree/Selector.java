package shit.lizz.modules.impl.misc.ai.btree;

import shit.lizz.modules.impl.misc.ai.Blackboard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Selector extends BTNode {

    private final List<BTNode> children;

    public Selector(BTNode... children) {
        this.children = new ArrayList<>(Arrays.asList(children));
    }

    @Override
    public Status tick(Blackboard bb) {
        for (BTNode child : children) {
            Status s = child.tick(bb);
            if (s == Status.SUCCESS || s == Status.RUNNING) {
                return status = s;
            }
        }
        return status = Status.FAILURE;
    }

    @Override
    public void reset() {
        super.reset();
        children.forEach(BTNode::reset);
    }
}
