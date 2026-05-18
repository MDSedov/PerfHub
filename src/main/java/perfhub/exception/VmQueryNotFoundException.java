package perfhub.exception;

public class VmQueryNotFoundException extends RuntimeException {

    public VmQueryNotFoundException(String type) {
        super("No VictoriaMetrics query configured for type: " + type);
    }
}
