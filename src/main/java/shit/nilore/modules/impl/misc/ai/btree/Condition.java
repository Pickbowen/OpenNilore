package shit.nilore.modules.impl.misc.ai.btree;

import shit.nilore.modules.impl.misc.ai.Blackboard;

import java.util.function.Predicate;

public class Condition extends BTNode {

    private final Predicate<Blackboard> predicate;

    public Condition(Predicate<Blackboard> predicate) {
        this.predicate = predicate;
    }

    @Override
    public Status tick(Blackboard bb) {
        return status = predicate.test(bb) ? Status.SUCCESS : Status.FAILURE;
    }
}
