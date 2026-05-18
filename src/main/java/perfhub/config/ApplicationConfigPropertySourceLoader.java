package perfhub.config;

import java.io.IOException;
import java.util.List;

import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.PropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;

/**
 * Allows Spring Boot to load properties from files with the {@code .config} extension,
 * treating them the same way as traditional {@code .properties} files.
 */
public class ApplicationConfigPropertySourceLoader implements PropertySourceLoader {

    private final PropertiesPropertySourceLoader delegate = new PropertiesPropertySourceLoader();

    @Override
    public String[] getFileExtensions() {
        return new String[] {"config"};
    }

    @Override
    public List<PropertySource<?>> load(String name, Resource resource) throws IOException {
        return delegate.load(name, resource);
    }
}
