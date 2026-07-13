package telegram.files;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static telegram.files.TransferVerticle.StuckTransferAction.*;
import static telegram.files.TransferVerticle.classifyStuckTransfer;

/**
 * Unit tests for the stuck-transfer classification (Phase 3). Pure, no DB/filesystem.
 * Inputs are SIZE-VERIFIED payload identity (from the persisted transfer_operation record):
 * {@code destIsPayload} = the destination exists AND its size matches record.source_size;
 * {@code tempIsPayload} = the temp exists AND matches; {@code sourceExists} = the source survives.
 * <p>
 * Contracts pinned: a size-matching payload at the destination recovers FORWARD (never re-download);
 * a size-matching temp recovers forward from the temp; otherwise the surviving source drives a retry;
 * only when nothing size-matching survives is it a genuine loss.
 */
class TransferReconciliationClassifyTest {

    // classifyStuckTransfer(destIsPayload, tempIsPayload, sourceExists)

    @Test
    @DisplayName("(a) size-verified payload at destination -> RECOVER_FORWARD_DEST (never re-download)")
    void payloadAtDestinationRecoversForward() {
        assertEquals(RECOVER_FORWARD_DEST, classifyStuckTransfer(true, false, false));
        // Destination-is-payload wins even if a temp/source also linger.
        assertEquals(RECOVER_FORWARD_DEST, classifyStuckTransfer(true, true, true));
    }

    @Test
    @DisplayName("(b) size-verified payload in the temp -> RECOVER_FORWARD_FROM_TEMP")
    void payloadInTempRecoversForward() {
        assertEquals(RECOVER_FORWARD_FROM_TEMP, classifyStuckTransfer(false, true, false));
        assertEquals(RECOVER_FORWARD_FROM_TEMP, classifyStuckTransfer(false, true, true));
    }

    @Test
    @DisplayName("(c) no payload at dest/temp but source survives -> RETRY_FROM_SOURCE (not a re-download)")
    void sourceSurvivesRetries() {
        assertEquals(RETRY_FROM_SOURCE, classifyStuckTransfer(false, false, true));
    }

    @Test
    @DisplayName("(d) no size-matching dest, no size-matching temp, no source -> REQUEUE_LOSS")
    void nothingSurvivesIsLoss() {
        assertEquals(REQUEUE_LOSS, classifyStuckTransfer(false, false, false));
    }
}
