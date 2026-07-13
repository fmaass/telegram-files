package telegram.files;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the crash-atomic transfer primitive {@link DurableTransfer} (Phase 3). Pure
 * filesystem, no DB, on the default SQLite {@code test} task.
 * <p>
 * The protocol under test: PERSIST first (before any FS mutation), COPY (never move) so the source
 * survives, size-verify + fsync the temp, atomic RENAME honoring the policy, fsync the dir, finalize,
 * and delete the SOURCE LAST (only after finalize). These tests pin the step ORDER and the invariant
 * that the source is never destroyed before the payload is confirmed at the destination.
 */
class DurableTransferTest {

    private Path writeSource(Path dir, String name, String content) throws IOException {
        Path src = dir.resolve(name);
        Files.writeString(src, content);
        return src;
    }

    @Test
    @DisplayName("happy path step ORDER: persist, copy, verify, fsync, rename, fsync-dir, finalize, delete-source (last)")
    void happyPathStepOrder(@TempDir Path base) throws IOException {
        Path srcDir = Files.createDirectories(base.resolve("inbox"));
        Path destDir = Files.createDirectories(base.resolve("dest"));
        Path source = writeSource(srcDir, "a.bin", "hello-world");
        Path finalPath = destDir.resolve("a.bin");

        List<String> persistOrder = new ArrayList<>();
        DurableTransfer.RecordPersister persister = (s, f, t, size) -> {
            persistOrder.add("persist");
            assertTrue(Files.exists(s), "source must still exist at persist time (persist is FIRST)");
            assertFalse(Files.exists(f), "final must not exist yet at persist time");
            assertEquals(Files.size(s), size, "persisted size must be the source size");
        };
        DurableTransfer.Finalizer finalizer = f -> {
            persistOrder.add("finalize");
            assertTrue(Files.exists(f), "payload must be at the destination before finalize");
            assertTrue(Files.exists(source), "source must STILL exist at finalize (deleted only after)");
            return true;
        };

        DurableTransfer.Result r = DurableTransfer.transferDurably(source, finalPath, false, persister, finalizer);
        List<DurableTransfer.Step> steps = r.steps();

        // Exact protocol order.
        assertEquals(List.of(
                DurableTransfer.Step.PERSIST_RECORD,
                DurableTransfer.Step.COPY_TO_TEMP,
                DurableTransfer.Step.VERIFY_STAGED,
                DurableTransfer.Step.FSYNC_FILE,
                DurableTransfer.Step.ATOMIC_RENAME,
                DurableTransfer.Step.FSYNC_DIR,
                DurableTransfer.Step.FINALIZE,
                DurableTransfer.Step.DELETE_SOURCE
        ), steps, "steps must follow the persist-first / source-deleted-last protocol");
        assertEquals(List.of("persist", "finalize"), persistOrder, "persist before finalize");

        assertTrue(Files.exists(finalPath), "final artifact present");
        assertEquals("hello-world", Files.readString(finalPath));
        assertFalse(Files.exists(source), "source deleted LAST after finalize");
        assertNoStagingTempsLeft(destDir);
    }

    @Test
    @DisplayName("copy-not-move: the source survives the copy step (a crash before rename leaves source intact)")
    void copyNotMoveSourceSurvives(@TempDir Path base) throws IOException {
        Path srcDir = Files.createDirectories(base.resolve("inbox"));
        Path destDir = Files.createDirectories(base.resolve("dest"));
        Path source = writeSource(srcDir, "b.bin", "payload-bytes");
        Path finalPath = destDir.resolve("b.bin");

        // A finalizer that throws AFTER the rename simulates a crash between rename and source-delete.
        // The source must still be present (it is only deleted after a SUCCESSFUL finalize).
        DurableTransfer.Finalizer crashingFinalizer = f -> {
            throw new IOException("simulated crash at finalize");
        };
        assertThrows(IOException.class,
                () -> DurableTransfer.transferDurably(source, finalPath, false, DurableTransfer.RecordPersister.NOOP, crashingFinalizer));

        assertTrue(Files.exists(source), "source must survive a finalize crash (copy-not-move, delete-last)");
        assertTrue(Files.exists(finalPath), "payload already renamed to destination before the finalize crash");
        assertEquals("payload-bytes", Files.readString(finalPath));
    }

    @Test
    @DisplayName("finalize NO-OP (returns false): source is NOT deleted (leftover left for reconciliation)")
    void finalizeNoOpKeepsSource(@TempDir Path base) throws IOException {
        Path srcDir = Files.createDirectories(base.resolve("inbox"));
        Path destDir = Files.createDirectories(base.resolve("dest"));
        Path source = writeSource(srcDir, "c.bin", "keep-me");
        Path finalPath = destDir.resolve("c.bin");

        DurableTransfer.Result r = DurableTransfer.transferDurably(source, finalPath, false,
                DurableTransfer.RecordPersister.NOOP, f -> false); // finalize did not apply (e.g. TOCTOU)

        assertFalse(r.steps().contains(DurableTransfer.Step.DELETE_SOURCE), "source must NOT be deleted on a no-op finalize");
        assertTrue(Files.exists(source), "source preserved for reconciliation when finalize did not apply");
        assertTrue(Files.exists(finalPath), "payload is at the destination");
    }

    // NOTE: the "crash between persist and rename" window (temp complete, source intact, reconcile from
    // persisted truth) is covered END-TO-END by the REAL reconciler in
    // TransferDurabilityPostgresTest.crashBetweenPersistAndRename_recoversFromTemp — a synthetic unit
    // test that hand-rolled the cleanup walk with no source/record/reconciler was removed as fake.

    @Test
    @DisplayName("size verification: a staged wrong-size artifact is REJECTED (no rename, no final file, source intact)")
    void stagedSizeMismatchIsRejected(@TempDir Path base) throws IOException {
        Path srcDir = Files.createDirectories(base.resolve("inbox"));
        Path destDir = Files.createDirectories(base.resolve("dest"));
        Path source = writeSource(srcDir, "f.bin", "the-full-source-bytes");
        Path finalPath = destDir.resolve("f.bin");

        // Inject a copier that stages a TRUNCATED artifact (wrong size) -> the size verify must reject it.
        DurableTransfer.Copier truncating = (src, temp) -> {
            Files.copy(src, temp);
            Files.writeString(temp, "short");
        };
        IOException ex = assertThrows(IOException.class, () -> DurableTransfer.transferDurably(
                source, finalPath, false, DurableTransfer.RecordPersister.NOOP, DurableTransfer.Finalizer.NOOP, truncating));
        assertTrue(ex.getMessage().contains("size mismatch"), "must fail size-mismatch, was: " + ex.getMessage());
        assertFalse(Files.exists(finalPath), "no final file on size mismatch");
        assertTrue(Files.exists(source), "source must survive (transfer did not complete)");
        assertNoStagingTempsLeft(destDir);
    }

    @Test
    @DisplayName("policy-preserving rename: an external DIFFERENT-size file at the destination is NOT clobbered when policy != OVERWRITE")
    void policyPreservingRenameDoesNotClobberExternalFile(@TempDir Path base) throws IOException {
        Path srcDir = Files.createDirectories(base.resolve("inbox"));
        Path destDir = Files.createDirectories(base.resolve("dest"));
        Path source = writeSource(srcDir, "g.bin", "our-payload");
        Path finalPath = destDir.resolve("g.bin");
        // An unrelated external file (different size) appeared at the destination after planning.
        Files.writeString(finalPath, "a-totally-different-external-file-larger");

        // overwrite=false (policy is not OVERWRITE) -> the rename must REFUSE to clobber.
        IOException ex = assertThrows(IOException.class, () -> DurableTransfer.transferDurably(
                source, finalPath, false, DurableTransfer.RecordPersister.NOOP, DurableTransfer.Finalizer.NOOP));
        assertTrue(ex.getMessage().contains("refusing to clobber"), "must refuse to clobber, was: " + ex.getMessage());
        assertEquals("a-totally-different-external-file-larger", Files.readString(finalPath),
                "the external file must be untouched");
        assertTrue(Files.exists(source), "source must survive the refused transfer");
        assertNoStagingTempsLeft(destDir);
    }

    @Test
    @DisplayName("OVERWRITE policy: a different-size destination file IS replaced when overwrite=true")
    void overwritePolicyReplacesDestination(@TempDir Path base) throws IOException {
        Path srcDir = Files.createDirectories(base.resolve("inbox"));
        Path destDir = Files.createDirectories(base.resolve("dest"));
        Path source = writeSource(srcDir, "h.bin", "new-payload");
        Path finalPath = destDir.resolve("h.bin");
        Files.writeString(finalPath, "old-different-content-here");

        DurableTransfer.Result r = DurableTransfer.transferDurably(source, finalPath, true,
                DurableTransfer.RecordPersister.NOOP, DurableTransfer.Finalizer.NOOP);
        assertTrue(r.steps().contains(DurableTransfer.Step.ATOMIC_RENAME));
        assertEquals("new-payload", Files.readString(finalPath), "OVERWRITE replaces the destination");
        assertFalse(Files.exists(source), "source deleted last after finalize");
    }

    @Test
    @DisplayName("idempotent replace: a SAME-size payload already at the destination is replaced (no clobber refusal)")
    void samePayloadAtDestinationIsIdempotentlyReplaced(@TempDir Path base) throws IOException {
        Path srcDir = Files.createDirectories(base.resolve("inbox"));
        Path destDir = Files.createDirectories(base.resolve("dest"));
        Path source = writeSource(srcDir, "i.bin", "identical");
        Path finalPath = destDir.resolve("i.bin");
        Files.writeString(finalPath, "identical"); // same size => treated as OUR payload

        // overwrite=false, but the existing file IS our payload by size -> idempotent replace, no refusal.
        DurableTransfer.Result r = DurableTransfer.transferDurably(source, finalPath, false,
                DurableTransfer.RecordPersister.NOOP, DurableTransfer.Finalizer.NOOP);
        assertTrue(r.steps().contains(DurableTransfer.Step.ATOMIC_RENAME));
        assertEquals("identical", Files.readString(finalPath));
        assertFalse(Files.exists(source), "source deleted last");
    }

    private void assertNoStagingTempsLeft(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> temps = walk.filter(Files::isRegularFile)
                    .filter(p -> DurableTransfer.isStagingTemp(p.getFileName().toString()))
                    .toList();
            assertTrue(temps.isEmpty(), "no dest-local staging temp must remain, found: " + temps);
        }
    }
}
