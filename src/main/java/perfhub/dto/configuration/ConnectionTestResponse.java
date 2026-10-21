package perfhub.dto.configuration;

public record ConnectionTestResponse(String service, boolean success, String message,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        perfhub.dto.grafana.GrafanaCatalogResult catalog) {
    public ConnectionTestResponse(String service, boolean success, String message) {
        this(service, success, message, null);
    }
}
