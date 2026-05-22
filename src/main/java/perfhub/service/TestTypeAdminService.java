package perfhub.service;

import perfhub.model.TestType;
import perfhub.repository.TestTypeRepository;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TestTypeAdminService {

    private static final Pattern CODE_PATTERN = Pattern.compile("[a-z0-9_-]+");
    private static final Map<String, String> STANDARD_NAMES = Map.of(
            "max", "Максимум",
            "stability", "Стабильность",
            "soak", "Надежность",
            "psu", "PSU",
            "dt", "DT");
    private static final Set<String> STANDARD_CODES = STANDARD_NAMES.keySet();

    private final TestTypeRepository testTypeRepository;

    public TestTypeAdminService(TestTypeRepository testTypeRepository) {
        this.testTypeRepository = testTypeRepository;
    }

    public List<TestType> listTypes() {
        return testTypeRepository.findAll().stream()
                .map(this::toModel)
                .toList();
    }

    @Transactional
    public TestType createType(TestTypeCommand command) {
        SanitizedTestType sanitized = sanitize(command);
        validateUniqueCode(sanitized.code(), null);
        int id = testTypeRepository.create(sanitized.code(), sanitized.name());
        return getType(id);
    }

    @Transactional
    public TestType updateType(int id, TestTypeCommand command) {
        TestType existing = getType(id);
        SanitizedTestType sanitized = sanitize(command);
        if (existing.standard() && !existing.code().equalsIgnoreCase(sanitized.code())) {
            throw new IllegalArgumentException("Код стандартного типа теста нельзя изменить");
        }
        validateUniqueCode(sanitized.code(), id);
        testTypeRepository.update(id, sanitized.code(), sanitized.name());
        return getType(id);
    }

    @Transactional
    public void deleteType(int id) {
        TestType existing = getType(id);
        if (existing.standard()) {
            throw new IllegalArgumentException("Стандартный тип теста нельзя удалить");
        }
        testTypeRepository.delete(id);
    }

    private TestType getType(int id) {
        return testTypeRepository.findById(id)
                .map(this::toModel)
                .orElseThrow(() -> new IllegalArgumentException("Тип теста не найден"));
    }

    private TestType toModel(TestTypeRepository.TestTypeRecord record) {
        String code = normalizeCode(record.code());
        String name = StringUtils.hasText(record.name())
                ? record.name().trim()
                : STANDARD_NAMES.getOrDefault(code, code);
        return new TestType(record.id(), code, name, STANDARD_CODES.contains(code));
    }

    private SanitizedTestType sanitize(TestTypeCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Данные типа теста не переданы");
        }
        String name = StringUtils.trimWhitespace(command.name());
        String code = normalizeCode(command.code());
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("Название теста обязательно");
        }
        if (name.length() > 128) {
            throw new IllegalArgumentException("Название теста должно быть не длиннее 128 символов");
        }
        if (!StringUtils.hasText(code)) {
            throw new IllegalArgumentException("Код теста обязателен");
        }
        if (code.length() > 64) {
            throw new IllegalArgumentException("Код теста должен быть не длиннее 64 символов");
        }
        if (!CODE_PATTERN.matcher(code).matches()) {
            throw new IllegalArgumentException("Код теста может содержать только латинские буквы, цифры, дефис и подчеркивание");
        }
        if ("unknown".equals(code)) {
            throw new IllegalArgumentException("Код unknown зарезервирован системой");
        }
        return new SanitizedTestType(name, code);
    }

    private void validateUniqueCode(String code, Integer excludedId) {
        if (testTypeRepository.existsCode(code, excludedId)) {
            throw new IllegalArgumentException("Тип теста с таким кодом уже существует");
        }
    }

    private String normalizeCode(String code) {
        String normalized = StringUtils.trimWhitespace(code);
        return normalized == null ? "" : normalized.toLowerCase(Locale.ROOT);
    }

    public record TestTypeCommand(String name, String code) {}

    private record SanitizedTestType(String name, String code) {}
}
