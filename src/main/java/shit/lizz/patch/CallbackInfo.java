package shit.lizz.patch;

public final class CallbackInfo {
    public Object result;
    public boolean cancelled;

    public CallbackInfo(Object result, boolean cancelled) {
        this.result = result;
        this.cancelled = cancelled;
    }

    public static CallbackInfo create(Object result) {
        return new CallbackInfo(result, false);
    }

    public void cancel() {
        this.cancelled = true;
    }
}
