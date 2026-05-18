package perfhub.exception;

public class VmDataNotCachedException extends RuntimeException {

    public VmDataNotCachedException() {
        super("VictoriaMetrics data is not cached yet");
    }
}
