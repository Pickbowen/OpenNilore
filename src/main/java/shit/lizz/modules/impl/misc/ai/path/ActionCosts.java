package shit.lizz.modules.impl.misc.ai.path;

public interface ActionCosts {
    double COST_INF = 1000000;
    double WALK_ONE_BLOCK_COST = 20.0 / 4.317;       // ~4.63 ticks
    double SPRINT_ONE_BLOCK_COST = 20.0 / 5.612;     // ~3.56 ticks
    double WALK_OFF_BLOCK_COST = WALK_ONE_BLOCK_COST * 0.8;
    double CENTER_AFTER_FALL_COST = 0.927;
    double JUMP_ONE_BLOCK_COST = 0.875;
    double PLACE_ONE_BLOCK_COST = WALK_ONE_BLOCK_COST * 2;
    double SNEAK_ONE_BLOCK_COST = 20.0 / 1.3;
}
