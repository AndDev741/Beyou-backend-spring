package beyou.beyouapp.backend.security.validators;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the safety check rejects production-shaped URLs and accepts
 * throwaway ones. Without this guard, a misconfigured e2e profile would
 * happily wipe the dev database on the next run.
 */
class E2eSafetyCheckTest {

    @Test
    @DisplayName("rejects empty URL")
    void rejectsEmptyUrl() {
        assertThatThrownBy(() -> verify(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("rejects the dev URL (no e2e/test in name)")
    void rejectsDevUrl() {
        assertThatThrownBy(() -> verify("jdbc:postgresql://localhost:5490/beyou"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not look like a throwaway database");
    }

    @Test
    @DisplayName("rejects a production-looking URL")
    void rejectsProdUrl() {
        assertThatThrownBy(() ->
                verify("jdbc:postgresql://prod-db.cluster.amazonaws.com:5432/beyou_prod"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("accepts a URL with 'e2e' in the database name")
    void acceptsE2eUrl() {
        // No exception means the check passed
        verify("jdbc:postgresql://localhost:5490/beyou_e2e");
    }

    @Test
    @DisplayName("accepts a URL with 'test' in the database name")
    void acceptsTestUrl() {
        verify("jdbc:postgresql://localhost:5490/beyou_test");
    }

    @Test
    @DisplayName("accepts uppercased variants (case-insensitive)")
    void acceptsUppercase() {
        verify("jdbc:postgresql://localhost:5490/BEYOU_E2E");
    }

    /** Build a check, inject a URL, run the verification. */
    private static void verify(String url) {
        E2eSafetyCheck check = new E2eSafetyCheck();
        ReflectionTestUtils.setField(check, "datasourceUrl", url);
        // Use reflection to invoke the package-private @PostConstruct method.
        ReflectionTestUtils.invokeMethod(check, "verifyDatasourceIsThrowaway");
        assertThat(true).as("verification passed without throwing").isTrue();
    }
}
