package telegram.files;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.util.StrUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.StructuredChatCompletionCreateParams;
import io.vertx.core.json.JsonObject;
import telegram.files.repository.FileRecord;
import telegram.files.repository.SettingAutoRecords;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

public abstract class Transfer {

    private static final Log log = LogFactory.get();

    public String destination;

    public TransferPolicy transferPolicy;

    public DuplicationPolicy duplicationPolicy;

    public boolean transferHistory;

    public boolean useCaptionName;

    public JsonObject extra;

    public Consumer<TransferStatusUpdated> transferStatusUpdated;

    /**
     * Persists the durable {@link telegram.files.repository.TransferOperationRecord} FIRST — before any
     * filesystem mutation. Wired by {@link TransferVerticle}.
     */
    public TransferOperationPersister transferOperationPersister;

    /**
     * CAS-finalizes {@code transfer_status} and deletes the durable record in one transaction, returning
     * true iff it applied. Wired by {@link TransferVerticle}. Runs AFTER the destination directory fsync
     * and BEFORE the source delete.
     */
    public TransferFinalizer transferFinalizer;

    /**
     * Deletes the durable transfer-operation record for a unique_id. Wired by {@link TransferVerticle}.
     * Used to clean up after a finalize no-op (the record would otherwise leak, since the row is now
     * externally terminal and excluded from reconciliation).
     */
    public TransferOperationCleaner transferOperationCleaner;

    private FileRecord transferRecord;

    private static final int MAX_CAPTION_NAME_LENGTH = 80;

    public Transfer(SettingAutoRecords.TransferRule transferRule) {
        this.destination = transferRule.destination;
        this.transferPolicy = transferRule.transferPolicy;
        this.duplicationPolicy = transferRule.duplicationPolicy;
        this.transferHistory = transferRule.transferHistory;
        this.useCaptionName = transferRule.useCaptionName;
        this.extra = transferRule.extra != null ? transferRule.extra : new JsonObject();
    }

    public static Transfer create(SettingAutoRecords.TransferRule transferRule) {
        return switch (transferRule.transferPolicy) {
            case DIRECT -> new DirectTransfer(transferRule);
            case GROUP_BY_CHAT -> new GroupByChat(transferRule);
            case GROUP_BY_TYPE -> new GroupByType(transferRule);
            case GROUP_BY_AI -> new GroupByAI(transferRule);
        };
    }

    public boolean isRuleUpdated(SettingAutoRecords.TransferRule transferRule) {
        return !Objects.equals(this.destination, transferRule.destination)
               || this.transferPolicy != transferRule.transferPolicy
               || this.duplicationPolicy != transferRule.duplicationPolicy
               || this.transferHistory != transferRule.transferHistory
               || this.useCaptionName != transferRule.useCaptionName
               || !Objects.equals(this.extra, transferRule.extra);
    }

    public void transfer(FileRecord fileRecord) {
        log.debug("Start transfer file {}", fileRecord.id());
        transferRecord = fileRecord;
        transferStatusUpdated.accept(new TransferStatusUpdated(fileRecord, FileRecord.TransferStatus.transferring, null));
        try {
            File originFile = new File(fileRecord.localPath());
            if (!originFile.exists()) {
                log.error("File {} not found: {}", fileRecord.id(), fileRecord.localPath());
                transferStatusUpdated.accept(new TransferStatusUpdated(fileRecord, FileRecord.TransferStatus.error, null));
                return;
            }

            String transferPath = getTransferPath(fileRecord);
            boolean isOverwrite = false;
            if (FileUtil.exist(transferPath)) {
                if (duplicationPolicy == DuplicationPolicy.SKIP) {
                    log.trace("Skip file {}", fileRecord.id());
                    transferStatusUpdated.accept(new TransferStatusUpdated(fileRecord, FileRecord.TransferStatus.idle, null));
                    return;
                }

                if (duplicationPolicy == DuplicationPolicy.OVERWRITE) {
                    log.trace("Overwrite file {}", fileRecord.id());
                    isOverwrite = true;
                }

                if (duplicationPolicy == DuplicationPolicy.RENAME) {
                    transferPath = getUniquePath(transferPath);
                    log.trace("Rename file {} to {}", fileRecord.id(), transferPath);
                }

                if (duplicationPolicy == DuplicationPolicy.HASH) {
                    if (MessyUtils.compareFilesMD5(FileUtil.file(fileRecord.localPath()), FileUtil.file(transferPath))) {
                        log.trace("File {} is the same as {}", fileRecord.id(), transferPath);
                        // The identical payload is ALREADY at the destination. Bring this dedup path under
                        // the persist-first / source-deleted-last invariant: persist a durable record
                        // pointing at the existing destination, finalize, and delete the source ONLY if the
                        // finalize applied. A crash before the source-delete then recovers by recognizing
                        // the size-verified payload at the destination — NEVER a spurious re-download.
                        finalizeExistingDestination(fileRecord, transferPath);
                        return;
                    } else {
                        transferPath = getUniquePath(transferPath);
                        log.trace("Rename file {} to {}", fileRecord.id(), transferPath);
                    }
                }
            }

            // Crash-atomic transfer (D6). Persist-first, copy-not-move, source-deleted-last (see
            // DurableTransfer): the SOURCE is the durable anchor and is never destroyed until the payload
            // is confirmed at the destination. The finalizer CAS-finalizes transfer_status + deletes the
            // record in one transaction; only if it applies is the source deleted.
            String sourcePath = fileRecord.localPath();
            String uniqueId = fileRecord.uniqueId();
            String policyName = duplicationPolicy == null ? null : duplicationPolicy.name();
            boolean overwrite = isOverwrite;
            DurableTransfer.RecordPersister persister = (src, finalPath, stagingTemp, sourceSize) -> {
                // Persist the durable record BEFORE any filesystem mutation (exact suffixed path, temp,
                // source size = payload identity, policy). NOOP if no persister wired (e.g. unit tests).
                if (transferOperationPersister != null) {
                    transferOperationPersister.persist(new telegram.files.repository.TransferOperationRecord(
                            uniqueId, finalPath.toString(), stagingTemp.toString(), src.toString(),
                            sourceSize, policyName, System.currentTimeMillis()));
                }
            };
            DurableTransfer.Finalizer finalizer = finalPath -> {
                // CAS-finalize transfer_status='completed' (+ delete the record) in one transaction.
                // Returns whether it applied so DurableTransfer only deletes the source on success.
                if (transferFinalizer != null) {
                    return transferFinalizer.finalize(uniqueId, finalPath.toString());
                }
                return true;
            };
            DurableTransfer.Result result = DurableTransfer.transferDurably(
                    Path.of(fileRecord.localPath()), Path.of(transferPath), overwrite, persister, finalizer);
            log.info("Transfer file {} to {}, duplication policy: {} overwrite: {} steps: {}",
                    fileRecord.id(), transferPath, duplicationPolicy, overwrite, result.steps());

            // Publish the completed event ONLY if the finalizer actually applied (FINALIZE step present).
            // A no-op finalize means the row went processed/imported in a TOCTOU window: do NOT publish a
            // spurious 'completed', and clean up our now-useless operation record so it does not leak
            // (processed/imported rows are excluded from reconciliation). The external terminal row itself
            // is left untouched.
            if (result.steps().contains(DurableTransfer.Step.FINALIZE)) {
                transferStatusUpdated.accept(new TransferStatusUpdated(fileRecord, FileRecord.TransferStatus.completed, transferPath));
            } else {
                log.warn("Transfer file {} finalize no-op (row externally terminal?); not publishing 'completed', cleaning operation record", fileRecord.id());
                if (transferOperationCleaner != null) {
                    transferOperationCleaner.clean(uniqueId);
                }
            }
        } catch (Exception e) {
            log.error(e, "Transfer file {} error", fileRecord.id());
            transferStatusUpdated.accept(new TransferStatusUpdated(fileRecord, FileRecord.TransferStatus.error, null));
        } finally {
            transferRecord = null;
        }
    }

    /**
     * HASH-dedup finalize: the identical payload is already at {@code destPath}. Bring it under the
     * persist-first / source-deleted-last invariant so a crash cannot cause a spurious re-download of a
     * file that is verifiably at the destination. Persist a durable record (source_path = the inbox
     * source, final_dest_path = the existing destination, source_size = the payload size) BEFORE deleting
     * the source; finalize; delete the source ONLY if the finalize applied. If the finalize no-ops
     * (row went processed/imported), clean the record and do not publish 'completed'.
     */
    private void finalizeExistingDestination(FileRecord fileRecord, String destPath) throws java.io.IOException {
        String uniqueId = fileRecord.uniqueId();
        String sourcePath = fileRecord.localPath();
        long payloadSize;
        try {
            payloadSize = java.nio.file.Files.size(Path.of(destPath));
        } catch (java.io.IOException e) {
            throw new java.io.IOException("HASH dedup: failed to stat existing destination " + destPath, e);
        }
        // (1) PERSIST FIRST — before deleting the source. staging_temp is null (no staging: the payload
        // is already at the destination). policy = HASH.
        if (transferOperationPersister != null) {
            transferOperationPersister.persist(new telegram.files.repository.TransferOperationRecord(
                    uniqueId, destPath, null, sourcePath, payloadSize,
                    DuplicationPolicy.HASH.name(), System.currentTimeMillis()));
        }
        // (2) finalize (CAS + delete record). Only on success do we delete the source and publish.
        boolean finalized = transferFinalizer == null || transferFinalizer.finalize(uniqueId, destPath);
        if (finalized) {
            FileUtil.del(sourcePath);
            transferStatusUpdated.accept(new TransferStatusUpdated(fileRecord, FileRecord.TransferStatus.completed, destPath));
        } else {
            log.warn("HASH dedup finalize no-op for {} (row externally terminal?); not deleting source, cleaning record", fileRecord.id());
            if (transferOperationCleaner != null) {
                transferOperationCleaner.clean(uniqueId);
            }
        }
    }

    private String getUniquePath(String path) {
        if (!FileUtil.exist(path)) {
            return path;
        }
        String name = FileUtil.getName(path);
        String parent = FileUtil.getParent(path, 1);
        String extension = FileUtil.extName(name);
        String baseName = FileUtil.mainName(name);
        int i = 1;
        while (FileUtil.exists(Path.of(parent, "%s-%d.%s".formatted(baseName, i, extension)), false)) {
            i++;
        }
        return Path.of(parent, "%s-%d.%s".formatted(baseName, i, extension)).toString();
    }

    /**
     * Builds the destination file name. When {@link #useCaptionName} is enabled and the file has a
     * caption, the caption is appended before the extension: {@code <originalBaseName>_<caption>.<ext>}.
     * Falls back to the original file name when disabled or when there is no usable caption.
     */
    protected String buildFileName(FileRecord fileRecord) {
        String originalName = FileUtil.getName(fileRecord.localPath());
        if (!useCaptionName || StrUtil.isBlank(fileRecord.caption())) {
            return originalName;
        }
        String caption = sanitizeForFileName(fileRecord.caption());
        if (StrUtil.isBlank(caption)) {
            return originalName;
        }
        String extension = FileUtil.extName(originalName);
        String baseName = FileUtil.mainName(originalName);
        return StrUtil.isBlank(extension)
                ? "%s_%s".formatted(baseName, caption)
                : "%s_%s.%s".formatted(baseName, caption, extension);
    }

    private static String sanitizeForFileName(String text) {
        if (text == null) {
            return "";
        }
        // Replace characters that are illegal/problematic in file names and collapse whitespace.
        String sanitized = text.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (sanitized.length() > MAX_CAPTION_NAME_LENGTH) {
            sanitized = sanitized.substring(0, MAX_CAPTION_NAME_LENGTH).trim();
        }
        return sanitized;
    }

    public FileRecord getTransferRecord() {
        return transferRecord;
    }

    protected abstract String getTransferPath(FileRecord fileRecord);

    static class GroupByChat extends Transfer {

        public GroupByChat(SettingAutoRecords.TransferRule transferRule) {
            super(transferRule);
        }

        @Override
        protected String getTransferPath(FileRecord fileRecord) {
            String name = buildFileName(fileRecord);
            return Path.of(destination,
                    Convert.toStr(fileRecord.telegramId()),
                    Convert.toStr(fileRecord.chatId()),
                    name
            ).toString();
        }
    }

    static class GroupByType extends Transfer {

        public GroupByType(SettingAutoRecords.TransferRule transferRule) {
            super(transferRule);
        }

        @Override
        protected String getTransferPath(FileRecord fileRecord) {
            String name = buildFileName(fileRecord);
            return Path.of(destination,
                    fileRecord.type(),
                    name
            ).toString();
        }
    }

    static class GroupByAI extends Transfer {
        private final OpenAIClient client;

        private final String promptTemplate;

        public GroupByAI(SettingAutoRecords.TransferRule transferRule) {
            super(transferRule);
            promptTemplate = extra.getString("promptTemplate");
            if (StrUtil.isBlank(promptTemplate)) {
                throw new IllegalArgumentException("Prompt template is required for AI classification transfer policy");
            }
            client = OpenAIOkHttpClient.fromEnv();
        }

        @Override
        protected String getTransferPath(FileRecord fileRecord) {
            Map<String, Object> fileMap = FileRecord.toMap(fileRecord);
            String prompt = StrFormatter.format(promptTemplate, fileMap, false);
            StructuredChatCompletionCreateParams<AIClassificationResult> createParams = ChatCompletionCreateParams.builder()
                    .model(Config.OPENAI_MODEL)
                    .responseFormat(AIClassificationResult.class)
                    .addUserMessage(prompt)
                    .build();

            AIClassificationResult result = client.chat().completions().create(createParams).choices().stream()
                    .flatMap(choice -> choice.message().content().stream())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No classification result from AI"));
            if (StrUtil.isBlank(result.path)) {
                throw new IllegalStateException("Invalid classification result from AI: " + result);
            }
            log.debug("File {} classified to {} by AI, reason: {}", fileRecord.id(), result.path, result.reason);
            String name = buildFileName(fileRecord);
            // Check if the path contains file extension
            if (StrUtil.isNotBlank(FileUtil.extName(result.path))) {
                name = "";
            }

            return Path.of(destination,
                    result.path,
                    name
            ).toString();
        }

    }

    static class DirectTransfer extends Transfer {

        public DirectTransfer(SettingAutoRecords.TransferRule transferRule) {
            super(transferRule);
        }

        @Override
        protected String getTransferPath(FileRecord fileRecord) {
            String name = buildFileName(fileRecord);
            return Path.of(destination, name).toString();
        }
    }

    public record TransferStatusUpdated(FileRecord fileRecord,
                                        FileRecord.TransferStatus transferStatus,
                                        String localPath) {
    }

    /** Persists a durable transfer-operation record (awaited) BEFORE any filesystem mutation. */
    @FunctionalInterface
    public interface TransferOperationPersister {
        void persist(telegram.files.repository.TransferOperationRecord operation) throws java.io.IOException;
    }

    /**
     * CAS-finalizes {@code transfer_status='completed'} for {@code uniqueId} (localPath = the final
     * destination) AND deletes the durable record in one transaction. Returns true iff it applied.
     */
    @FunctionalInterface
    public interface TransferFinalizer {
        boolean finalize(String uniqueId, String finalPath) throws java.io.IOException;
    }

    /** Deletes the durable transfer-operation record for a unique_id (awaited). */
    @FunctionalInterface
    public interface TransferOperationCleaner {
        void clean(String uniqueId) throws java.io.IOException;
    }

    public enum TransferPolicy {
        /**
         * Transfer files to the specified destination without grouping
         */
        DIRECT,
        /**
         * Transfer files by chat id
         */
        GROUP_BY_CHAT,
        /**
         * Transfer files by type
         */
        GROUP_BY_TYPE,
        /**
         * Transfer files by AI classification
         */
        GROUP_BY_AI,
    }

    public enum DuplicationPolicy {
        /**
         * Overwrite the existing file
         */
        OVERWRITE,
        /**
         * Rename the file with a suffix
         */
        RENAME,
        /**
         * Skip the file
         */
        SKIP,
        /**
         * Calculate the hash of the file and compare with the existing file, if the hash is the same,
         * delete the original file and set the local path to the existing file, otherwise, move the file
         */
        HASH,
    }

    @JsonClassDescription("AI Classification Result")
    static class AIClassificationResult {
        @JsonProperty
        @JsonPropertyDescription("A relative path for classification, e.g., images/nature, documents/work/example.pdf")
        public String path;

        @JsonProperty
        @JsonPropertyDescription("Reason for classification or can't classify")
        public String reason;
    }
}
