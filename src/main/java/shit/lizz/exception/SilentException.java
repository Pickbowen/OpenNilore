package shit.lizz.exception;

import java.io.PrintStream;
import java.io.PrintWriter;

public class SilentException
extends RuntimeException {
    public SilentException() {
        this.setStackTrace(new StackTraceElement[0]);
    }

    public void printStackTrace(PrintStream printStream) {
    }

    public void printStackTrace(PrintWriter printWriter) {
    }

    public void printStackTrace() {
    }
}