package perfhub.dto.confluence;

import java.util.List;

public record ConfluencePageDescendant(
        String id,
        String title,
        List<String> ancestorIds) {
}
