package telegram.files;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4 (D4 safe publication): every {@link TelegramVerticle} field written on the verticle's own
 * context but READ from another verticle/context (Http/AutoDownload/Preload/AutomationsHolder) must
 * be safely published. This structural test pins them {@code volatile} so a refactor that drops the
 * modifier — reintroducing the visibility race — fails here.
 */
class TelegramVerticleFieldVisibilityTest {

    private static final List<String> CROSS_CONTEXT_VOLATILE_FIELDS = List.of(
            "client",
            "authorized",
            "lastAuthorizationState",
            "lastConnectionState",
            "telegramRecord",
            "proxyName",
            "avgSpeed",
            "lastFileEventTime",
            "lastFileDownloadEventTime"
    );

    @Test
    @DisplayName("cross-context mutable fields are volatile (safe publication)")
    void crossContextFieldsAreVolatile() throws Exception {
        for (String name : CROSS_CONTEXT_VOLATILE_FIELDS) {
            Field f = TelegramVerticle.class.getDeclaredField(name);
            assertTrue(Modifier.isVolatile(f.getModifiers()),
                    "field '" + name + "' is read cross-context and MUST be volatile for safe publication");
        }
    }
}
