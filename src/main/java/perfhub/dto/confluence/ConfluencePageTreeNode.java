package perfhub.dto.confluence;

import java.util.List;

public record ConfluencePageTreeNode(
        String id,
        String title,
        String pageUrl,
        List<ConfluencePageTreeNode> children) {
}
