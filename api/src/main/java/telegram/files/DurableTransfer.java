package telegram.files;

import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Crash-atomic file transfer primitive (Phase 3, fixes D6: the TRANSFER step is not crash-atomic).
 * <p>
 * The download itself is durable — TDLib guarantees the downloaded artifact exists at its
 * {@code local_path} when it reports the download completed; this class does NOT re-implement that.
 * The gap this closes is the SECOND filesystem step: moving the completed artifact from the TDLib
 * inbox to its final destination.
 * <p>
 * <b>Core invariant:</b> at EVERY crash point at least one COMPLETE copy of the payload exists and is
 * discoverable from the persisted {@link telegram.files.repository.TransferOperationRecord}. The
 * SOURCE is the durable anchor and is NEVER destroyed until the payload is confirmed at the
 * destination. The protocol, in strict order:
 * <ol>
 *   <li><b>PERSIST the record FIRST</b> — before ANY filesystem mutation — with the source path, the
 *       exact final destination (incl. RENAME suffix), the temp path, the source SIZE (payload
 *       identity) and the duplication policy;</li>
 *   <li><b>COPY (never move)</b> source -> dest-local temp. Even on the same filesystem we COPY, so the
 *       source survives as a complete copy; verify {@code temp size == source size}; fsync the temp;</li>
 *   <li><b>atomic RENAME</b> temp -> final destination (dest-local temp => same-FS => atomic), honoring
 *       the duplication policy so an unrelated external file at the destination is not blindly clobbered;</li>
 *   <li><b>fsync the destination directory</b> (after the rename);</li>
 *   <li><b>finalize</b> (caller: CAS {@code transfer_status} + delete the record in one transaction);</li>
 *   <li><b>delete the SOURCE</b> only AFTER the finalize+record-delete commit.</li>
 * </ol>
 * Crash windows and their sole-copy anchor: before/after persist — source intact; after copy, before
 * rename — source intact (+ a complete temp); after rename, before finalize — payload at destination
 * (+ source still intact); after finalize, before source-delete — payload at destination, record gone
 * (nothing to reconcile). No window destroys the only copy.
 */
public final class DurableTransfer {

    private static final Log log = LogFactory.get();

    /** Suffix marking a dest-local staging temp file. Reconciliation matches on this. */
    public static final String TEMP_SUFFIX = ".part";

    /** Infix identifying our staging temps so reconciliation only removes ours. */
    public static final String TEMP_INFIX = ".tf-";

    private DurableTransfer() {
    }

    /**
     * Records, in call order, the durable steps performed. Exposed so a unit test can assert the
     * ordering (persist first; copy not move; directory flush after rename; source deleted last).
     */
    public enum Step {
        PERSIST_RECORD,   // durable record persisted BEFORE any filesystem mutation
        COPY_TO_TEMP,     // source COPIED (not moved) into the dest-local temp
        VERIFY_STAGED,    // staged artifact present + size verified against the source
        FSYNC_FILE,       // staged file forced to stable storage
        ATOMIC_RENAME,    // temp atomically renamed onto the final path (policy-honored)
        FSYNC_DIR,        // destination directory flushed AFTER the rename
        FINALIZE,         // caller finalized (CAS transfer_status + delete record)
        DELETE_SOURCE     // source deleted LAST, only after the finalize commit
    }

    /** Result of a durable transfer: the final path plus the ordered steps that were executed. */
    public record Result(Path finalPath, List<Step> steps) {
    }

    /** Persist the durable record BEFORE any filesystem mutation (source size supplied). */
    @FunctionalInterface
    public interface RecordPersister {
        void persist(Path source, Path finalPath, Path stagingTemp, long sourceSize) throws IOException;

        RecordPersister NOOP = (s, f, t, size) -> {
        };
    }

    /**
     * Finalize AFTER the destination directory is fsynced: the caller CAS-finalizes transfer_status and
     * deletes the durable record in one transaction. Returns true iff the finalize applied (a false
     * return — e.g. the row went processed/imported in a TOCTOU window — means the source must NOT be
     * deleted here; reconciliation owns the leftover).
     */
    @FunctionalInterface
    public interface Finalizer {
        boolean finalizeTransfer(Path finalPath) throws IOException;

        Finalizer NOOP = f -> true;
    }

    /**
     * Perform a crash-atomic transfer of {@code source} to {@code finalPath} following the persist-first,
     * copy-not-move, source-deleted-last protocol. {@code overwrite} true means the duplication policy is
     * OVERWRITE (an existing destination may be replaced). The {@code persister} is invoked FIRST (before
     * any filesystem mutation); the {@code finalizer} after the destination directory fsync; the source is
     * deleted only if the finalizer returns true.
     *
     * @return a {@link Result} with the final path and the ordered steps (for test assertions).
     * @throws IOException on any failure; the dest-local temp is best-effort cleaned so no partial
     *                     artifact leaks. The source is NEVER deleted on the failure path.
     */
    public static Result transferDurably(Path source, Path finalPath, boolean overwrite,
                                         RecordPersister persister, Finalizer finalizer) throws IOException {
        return transferDurably(source, finalPath, overwrite, persister, finalizer, DEFAULT_COPIER);
    }

    /** Convenience overload with no persist/finalize hooks (pure filesystem primitive; used by tests). */
    public static Result transferDurably(Path source, Path finalPath, boolean overwrite) throws IOException {
        return transferDurably(source, finalPath, overwrite, RecordPersister.NOOP, Finalizer.NOOP, DEFAULT_COPIER);
    }

    /**
     * Pluggable copy step (source -> dest-local temp). Always a COPY (never a move) so the source
     * survives. A test may inject a copier that stages a WRONG-SIZE artifact to exercise the
     * size-mismatch rejection. This is a real seam over the same {@link Files#copy} primitive — not a
     * mock of the unit under test.
     */
    @FunctionalInterface
    interface Copier {
        void copy(Path source, Path temp) throws IOException;
    }

    static final Copier DEFAULT_COPIER = (source, temp) ->
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);

    static Result transferDurably(Path source, Path finalPath, boolean overwrite,
                                  RecordPersister persister, Finalizer finalizer, Copier copier) throws IOException {
        List<Step> steps = new ArrayList<>();
        Path parent = finalPath.toAbsolutePath().getParent();
        if (parent == null) {
            throw new IOException("Destination has no parent directory: " + finalPath);
        }
        Files.createDirectories(parent);

        // Dest-local temp: SAME directory as the final path => provably same filesystem => the
        // temp->final rename is atomic. A unique infix keeps reconciliation from touching unrelated files.
        String tempName = "." + finalPath.getFileName().toString() + TEMP_INFIX
                + Long.toHexString(System.nanoTime()) + TEMP_SUFFIX;
        Path temp = parent.resolve(tempName);

        long sourceSize = Files.size(source);

        // (1) PERSIST FIRST — before ANY filesystem mutation. A crash here or immediately after leaves
        // the source intact (nothing has been touched yet). If the persist fails we abort BEFORE staging.
        persister.persist(source, finalPath, temp, sourceSize);
        steps.add(Step.PERSIST_RECORD);

        try {
            // (2) COPY (never move) source -> dest-local temp. The source survives as a complete copy.
            copier.copy(source, temp);
            steps.add(Step.COPY_TO_TEMP);

            // (3) verify the staged temp is present and its size matches the source (payload identity).
            if (!Files.exists(temp)) {
                throw new IOException("Staged temp missing after copy: " + temp);
            }
            long stagedSize = Files.size(temp);
            if (stagedSize != sourceSize) {
                throw new IOException("Staged size mismatch for %s: expected %d, got %d"
                        .formatted(finalPath, sourceSize, stagedSize));
            }
            steps.add(Step.VERIFY_STAGED);

            // (4) fsync the staged file BEFORE the rename.
            fsyncFile(temp);
            steps.add(Step.FSYNC_FILE);

            // (5) atomic rename temp -> final, honoring the policy (do not blindly clobber an external file).
            renameHonoringPolicy(temp, finalPath, overwrite, sourceSize);
            steps.add(Step.ATOMIC_RENAME);

            // (6) fsync the destination directory AFTER the rename so the directory entry is durable.
            fsyncDir(parent);
            steps.add(Step.FSYNC_DIR);
        } catch (IOException e) {
            // Best-effort cleanup of the dest-local temp so a failed transfer leaves no partial artifact.
            // The SOURCE is never deleted on this path — it remains the complete copy for a retry.
            try {
                Files.deleteIfExists(temp);
            } catch (IOException cleanupErr) {
                log.warn("Failed to clean staging temp {} after transfer error: {}", temp, cleanupErr.getMessage());
            }
            throw e;
        }

        // (7) finalize: CAS transfer_status + delete the record in one transaction. If it did NOT apply
        // (e.g. the row went processed/imported in a TOCTOU window), DO NOT delete the source — leave the
        // leftover for reconciliation to own.
        boolean finalized = finalizer.finalizeTransfer(finalPath);
        if (finalized) {
            steps.add(Step.FINALIZE);
            // (8) delete the SOURCE only AFTER the finalize+record-delete commit.
            Files.deleteIfExists(source);
            steps.add(Step.DELETE_SOURCE);
        } else {
            log.warn("Transfer finalize did not apply for {} (externally terminal?); leaving source and payload for reconciliation", finalPath);
        }

        return new Result(finalPath, steps);
    }

    /**
     * Atomically rename {@code stagingTemp} onto {@code finalPath}, honoring the duplication policy —
     * WITHOUT a clobber-inducing check-then-rename race. The strategy leads with the ATOMIC, NON-replacing
     * move and only decides on collision:
     * <ul>
     *   <li>attempt an atomic move that FAILS if the target exists ({@link java.nio.file.FileAlreadyExistsException}).
     *       This is race-free: a file appearing concurrently makes the move fail rather than be clobbered;</li>
     *   <li>on collision, replace ONLY when the existing file IS our payload (size matches — idempotent
     *       arrival) or the policy explicitly permits OVERWRITE;</li>
     *   <li>otherwise it is an unrelated external file: refuse (throw) rather than clobber. The
     *       SKIP/RENAME/HASH policies already chose a unique/target path at plan time; reaching here means
     *       a foreign file appeared AFTER planning.</li>
     * </ul>
     */
    static void renameHonoringPolicy(Path stagingTemp, Path finalPath, boolean overwrite, long payloadSize) throws IOException {
        // NOTE on atomicity vs. fail-if-exists: on POSIX, ATOMIC_MOVE maps to rename(2), which SILENTLY
        // OVERWRITES an existing target — so a bare ATOMIC_MOVE cannot be used as a "fail if exists" guard.
        // A plain Files.move (NO options) is contractually the create-guarded variant: it throws
        // FileAlreadyExistsException if the target exists and never clobbers. We therefore LEAD with the
        // create-guarded move (race-free: a foreign file appearing concurrently makes it fail, not clobber)
        // and only on a real collision decide, passing REPLACE_EXISTING ONLY when the existing file is our
        // payload (size match — idempotent arrival) or the policy is OVERWRITE.
        try {
            Files.move(stagingTemp, finalPath);
            return;
        } catch (java.nio.file.FileAlreadyExistsException collision) {
            long existingSize;
            try {
                existingSize = Files.size(finalPath);
            } catch (java.nio.file.NoSuchFileException vanished) {
                // The colliding file vanished between the failed move and the stat: retry the create-guarded move.
                Files.move(stagingTemp, finalPath);
                return;
            }
            if (existingSize == payloadSize || overwrite) {
                // Authorized replace (our payload already there, or OVERWRITE policy) — use the atomic
                // replacing move so the swap onto the final path is a single rename.
                Files.move(stagingTemp, finalPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return;
            }
            throw new IOException(("Destination %s already holds a DIFFERENT file (size %d != payload %d) and policy is not "
                    + "OVERWRITE; refusing to clobber — follow the duplication policy").formatted(finalPath, existingSize, payloadSize));
        }
    }

    /**
     * Complete a crashed rename during reconciliation: atomically rename an existing staging temp onto
     * its final destination (honoring the policy) and fsync the destination directory. Used when the temp
     * is a size-verified complete copy and the destination does not already hold the payload.
     */
    public static void completeRename(Path stagingTemp, Path finalPath, boolean overwrite, long payloadSize) throws IOException {
        Path parent = finalPath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        renameHonoringPolicy(stagingTemp, finalPath, overwrite, payloadSize);
        if (parent != null) {
            fsyncDir(parent);
        }
    }

    private static void fsyncFile(Path file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw");
             FileChannel ch = raf.getChannel()) {
            ch.force(true);
        }
    }

    /**
     * fsync a directory so a rename within it is durable. Not all platforms permit opening a
     * directory for a channel force (Windows notably does not); a failure to fsync the directory is
     * logged and tolerated rather than failing the whole transfer, since the file bytes themselves
     * are already fsynced and renamed.
     */
    private static void fsyncDir(Path dir) {
        try (FileChannel ch = FileChannel.open(dir)) {
            ch.force(true);
        } catch (IOException e) {
            log.debug("Directory fsync not supported/failed for {} ({}); file bytes already fsynced",
                    dir, e.getMessage());
        }
    }

    /**
     * Whether {@code name} is one of our dest-local staging temps (used by reconciliation cleanup).
     */
    public static boolean isStagingTemp(String name) {
        return name != null && name.contains(TEMP_INFIX) && name.endsWith(TEMP_SUFFIX);
    }
}
