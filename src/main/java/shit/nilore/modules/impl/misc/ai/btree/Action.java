package shit.nilore.modules.impl.misc.ai.btree;

import shit.nilore.modules.impl.misc.ai.Blackboard;

import java.util.function.Function;

public class Action extends BTNode {

    private final Function<Blackboard, Status> action;

    public Action(Function<Blackboard, Status> action) {
        this.action = action;
    }

    public static Action once(java.util.function.Consumer<Blackboard> action) {
        return new Action(bb -> {
            action.accept(bb);
            return Status.SUCCESS;
        });
    }

    public static Action until(java.util.function.Predicate<Blackboard> shouldContinue) {
        return new Action(bb -> shouldContinue.test(bb) ? Status.RUNNING : Status.SUCCESS);
    }

    @Override
    public Status tick(Blackboard bb) {
        return status = action.apply(bb);
    }
}
