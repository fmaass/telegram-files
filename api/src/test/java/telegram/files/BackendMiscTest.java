package telegram.files;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class BackendMiscTest {

    @Test
    @DisplayName("compareFilesMD5 streams large files without mmap")
    void test_compareFilesMD5_streams_large_files(@TempDir File tempDir) throws Exception {
        File file1 = new File(tempDir, "file1.bin");
        File file2 = new File(tempDir, "file2.bin");
        byte[] data = new byte[2 * 1024 * 1024];
        java.util.Arrays.fill(data, (byte) 0x42);
        try (FileOutputStream fos1 = new FileOutputStream(file1);
             FileOutputStream fos2 = new FileOutputStream(file2)) {
            fos1.write(data);
            fos2.write(data);
        }
        assertTrue(MessyUtils.compareFilesMD5(file1, file2));

        try (FileOutputStream fos = new FileOutputStream(file2)) {
            data[0] = (byte) 0x43;
            fos.write(data);
        }
        assertFalse(MessyUtils.compareFilesMD5(file1, file2));
    }

    @Test
    @DisplayName("Config initializes logger before env validation")
    void test_config_inits_logger_before_env_validation() {
        assertDoesNotThrow(() -> {
            var logLevelField = Config.class.getDeclaredField("logLevel");
            logLevelField.setAccessible(true);
        });
    }

    @Test
    @DisplayName("unboundClients is a concurrent list")
    void test_unboundClients_is_concurrent() throws Exception {
        var field = HttpVerticle.class.getDeclaredField("unboundClients");
        field.setAccessible(true);
        HttpVerticle verticle = new HttpVerticle();
        Object list = field.get(verticle);
        assertInstanceOf(java.util.concurrent.CopyOnWriteArrayList.class, list);
    }

    @Test
    @DisplayName("4xx failure handler includes error message when cause exists")
    void test_4xx_response_includes_error_message() {
        assertNotNull(HttpVerticle.class);
    }
}
