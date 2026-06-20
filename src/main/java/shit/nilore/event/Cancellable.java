package shit.nilore.event;

public interface Cancellable {
    boolean isCancelled();

    void setCancelled(boolean var1);
}