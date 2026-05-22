package perfhub.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import perfhub.repository.TestTypeRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TestTypeAdminServiceTest {

    @Mock
    private TestTypeRepository testTypeRepository;

    @Test
    void listTypesMarksDefaultCodesAsStandard() {
        TestTypeAdminService service = new TestTypeAdminService(testTypeRepository);
        when(testTypeRepository.findAll()).thenReturn(List.of(
                new TestTypeRepository.TestTypeRecord(1, "max", "Максимум"),
                new TestTypeRepository.TestTypeRecord(2, "custom", "Пользовательский")));

        assertThat(service.listTypes())
                .extracting("code", "name", "standard")
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("max", "Максимум", true),
                        org.assertj.core.api.Assertions.tuple("custom", "Пользовательский", false));
    }

    @Test
    void createTypeStoresNormalizedCode() {
        TestTypeAdminService service = new TestTypeAdminService(testTypeRepository);
        when(testTypeRepository.existsCode("smoke_test", null)).thenReturn(false);
        when(testTypeRepository.create("smoke_test", "Smoke")).thenReturn(10);
        when(testTypeRepository.findById(10))
                .thenReturn(Optional.of(new TestTypeRepository.TestTypeRecord(10, "smoke_test", "Smoke")));

        assertThat(service.createType(new TestTypeAdminService.TestTypeCommand(" Smoke ", " SMOKE_Test ")).code())
                .isEqualTo("smoke_test");
    }

    @Test
    void createTypeRejectsDuplicateCode() {
        TestTypeAdminService service = new TestTypeAdminService(testTypeRepository);
        when(testTypeRepository.existsCode("max", null)).thenReturn(true);

        assertThatThrownBy(() -> service.createType(new TestTypeAdminService.TestTypeCommand("Максимум", "max")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Тип теста с таким кодом уже существует");
    }

    @Test
    void updateTypeRejectsStandardCodeChange() {
        TestTypeAdminService service = new TestTypeAdminService(testTypeRepository);
        when(testTypeRepository.findById(1))
                .thenReturn(Optional.of(new TestTypeRepository.TestTypeRecord(1, "max", "Максимум")));

        assertThatThrownBy(() -> service.updateType(
                        1,
                        new TestTypeAdminService.TestTypeCommand("Максимум", "maximum")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Код стандартного типа теста нельзя изменить");
    }

    @Test
    void deleteTypeRejectsStandardType() {
        TestTypeAdminService service = new TestTypeAdminService(testTypeRepository);
        when(testTypeRepository.findById(1))
                .thenReturn(Optional.of(new TestTypeRepository.TestTypeRecord(1, "max", "Максимум")));

        assertThatThrownBy(() -> service.deleteType(1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Стандартный тип теста нельзя удалить");
    }

    @Test
    void deleteTypeDeletesCustomType() {
        TestTypeAdminService service = new TestTypeAdminService(testTypeRepository);
        when(testTypeRepository.findById(7))
                .thenReturn(Optional.of(new TestTypeRepository.TestTypeRecord(7, "smoke", "Smoke")));

        service.deleteType(7);

        verify(testTypeRepository).delete(7);
    }

    @Test
    void createTypeRejectsInvalidCodeBeforeRepositoryCall() {
        TestTypeAdminService service = new TestTypeAdminService(testTypeRepository);

        assertThatThrownBy(() -> service.createType(new TestTypeAdminService.TestTypeCommand("Smoke", "дым")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Код теста может содержать только латинские буквы, цифры, дефис и подчеркивание");
        verifyNoInteractions(testTypeRepository);
    }
}
