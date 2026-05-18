package perfhub.dto.gigachat;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GigachatTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_at") String expiresAt,
        @JsonProperty("expires_in") Long expiresIn,
        @JsonProperty("scope") String scope) {
}
