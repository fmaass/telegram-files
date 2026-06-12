package telegram.files;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import telegram.files.repository.FileRecord.DownloadStatus;

import static org.junit.jupiter.api.Assertions.*;

public class DownloadStatusTransitionTest {

    @Test
    @DisplayName("Full transition matrix: every legal from→to pair accepted")
    void test_download_status_transition_matrix() {
        assertTrue(DownloadStatus.idle.canTransitionTo(DownloadStatus.downloading));
        assertTrue(DownloadStatus.idle.canTransitionTo(DownloadStatus.error));
        assertFalse(DownloadStatus.idle.canTransitionTo(DownloadStatus.completed));
        assertFalse(DownloadStatus.idle.canTransitionTo(DownloadStatus.paused));

        assertTrue(DownloadStatus.downloading.canTransitionTo(DownloadStatus.paused));
        assertTrue(DownloadStatus.downloading.canTransitionTo(DownloadStatus.completed));
        assertTrue(DownloadStatus.downloading.canTransitionTo(DownloadStatus.error));
        assertTrue(DownloadStatus.downloading.canTransitionTo(DownloadStatus.idle));
        assertFalse(DownloadStatus.downloading.canTransitionTo(DownloadStatus.processed));

        assertTrue(DownloadStatus.paused.canTransitionTo(DownloadStatus.downloading));
        assertTrue(DownloadStatus.paused.canTransitionTo(DownloadStatus.idle));
        assertTrue(DownloadStatus.paused.canTransitionTo(DownloadStatus.error));
        assertFalse(DownloadStatus.paused.canTransitionTo(DownloadStatus.completed));

        assertTrue(DownloadStatus.completed.canTransitionTo(DownloadStatus.processed));
        assertTrue(DownloadStatus.completed.canTransitionTo(DownloadStatus.error));
        assertFalse(DownloadStatus.completed.canTransitionTo(DownloadStatus.downloading));

        assertTrue(DownloadStatus.processed.canTransitionTo(DownloadStatus.imported));
        assertFalse(DownloadStatus.processed.canTransitionTo(DownloadStatus.downloading));

        assertFalse(DownloadStatus.imported.canTransitionTo(DownloadStatus.downloading));
        assertFalse(DownloadStatus.imported.canTransitionTo(DownloadStatus.idle));

        assertTrue(DownloadStatus.error.canTransitionTo(DownloadStatus.idle));
        assertTrue(DownloadStatus.error.canTransitionTo(DownloadStatus.downloading));
        assertFalse(DownloadStatus.error.canTransitionTo(DownloadStatus.completed));
    }

    @Test
    @DisplayName("completed must NOT regress to downloading")
    void test_completed_does_not_regress_to_downloading() {
        assertFalse(DownloadStatus.completed.canTransitionTo(DownloadStatus.downloading));
    }

    @Test
    @DisplayName("processed and imported are preserved (no regression)")
    void test_processed_imported_preserved() {
        assertFalse(DownloadStatus.imported.canTransitionTo(DownloadStatus.idle));
        assertFalse(DownloadStatus.imported.canTransitionTo(DownloadStatus.downloading));
        assertFalse(DownloadStatus.imported.canTransitionTo(DownloadStatus.completed));
        assertFalse(DownloadStatus.processed.canTransitionTo(DownloadStatus.idle));
        assertFalse(DownloadStatus.processed.canTransitionTo(DownloadStatus.downloading));
    }

    @Test
    @DisplayName("Terminal states identified correctly")
    void test_terminal_states() {
        assertTrue(DownloadStatus.completed.isTerminal());
        assertTrue(DownloadStatus.processed.isTerminal());
        assertTrue(DownloadStatus.imported.isTerminal());
        assertTrue(DownloadStatus.error.isTerminal());
        assertFalse(DownloadStatus.idle.isTerminal());
        assertFalse(DownloadStatus.downloading.isTerminal());
        assertFalse(DownloadStatus.paused.isTerminal());
    }

    @Test
    @DisplayName("Self-transitions are allowed")
    void test_self_transitions() {
        for (DownloadStatus status : DownloadStatus.values()) {
            assertTrue(status.canTransitionTo(status),
                    "Self-transition for %s should be allowed".formatted(status));
        }
    }
}
