package io.b2mash.maestro.rollingnode.workflow;

/** The saga's own failure: the only reason a rolling-upgrade workflow may end {@code FAILED}. */
public class RollingBusinessFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** @param message what was refused */
    public RollingBusinessFailure(String message) {
        super(message);
    }
}
