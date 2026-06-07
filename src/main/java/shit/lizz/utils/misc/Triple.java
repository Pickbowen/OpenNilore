package shit.lizz.utils.misc;

public record Triple<A, B, C>(A first, B second, C third) {

    public boolean isEnabled() {
        if (this.third instanceof Boolean b) {
            return b;
        }
        return false;
    }
}
