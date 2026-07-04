package beyou.beyouapp.backend.security.validators;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the single-schema-owner interlock: with Flyway enabled, any
 * mutating Hibernate ddl-auto must refuse boot. Without this guard, a stray
 * ddl-auto=update would silently fork the schema outside flyway_schema_history.
 */
class SchemaOwnershipGuardTest {

    @Test
    @DisplayName("allows validate when Flyway is enabled")
    void allowsValidate() {
        assertThatCode(() -> verify(true, "validate")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("allows none when Flyway is enabled")
    void allowsNone() {
        assertThatCode(() -> verify(true, "none")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("is case-insensitive on the safe values")
    void allowsValidateAnyCase() {
        assertThatCode(() -> verify(true, "VALIDATE")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("refuses update when Flyway is enabled")
    void refusesUpdate() {
        assertThatThrownBy(() -> verify(true, "update"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REFUSING TO START");
    }

    @Test
    @DisplayName("refuses create-drop when Flyway is enabled")
    void refusesCreateDrop() {
        assertThatThrownBy(() -> verify(true, "create-drop"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("create-drop");
    }

    @Test
    @DisplayName("permits any ddl-auto when Flyway is explicitly disabled (baseline generation)")
    void flywayDisabledIsUnguarded() {
        assertThatCode(() -> verify(false, "create")).doesNotThrowAnyException();
    }

    private void verify(boolean flywayEnabled, String ddlAuto) {
        SchemaOwnershipGuard guard = new SchemaOwnershipGuard();
        ReflectionTestUtils.setField(guard, "flywayEnabled", flywayEnabled);
        ReflectionTestUtils.setField(guard, "ddlAuto", ddlAuto);
        ReflectionTestUtils.invokeMethod(guard, "verifySingleSchemaOwner");
    }
}
