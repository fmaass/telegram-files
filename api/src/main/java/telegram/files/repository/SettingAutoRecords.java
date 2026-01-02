package telegram.files.repository;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.vertx.core.json.JsonObject;
import telegram.files.MessyUtils;
import telegram.files.transfer.Transfer;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class SettingAutoRecords {
    public List<Automation> automations;

    /**
     * @deprecated Use AutomationState enum instead.
     */
    @Deprecated
    public static final int HISTORY_PRELOAD_STATE = 1;

    /**
     * @deprecated Use AutomationState enum instead.
     */
    @Deprecated
    public static final int HISTORY_DOWNLOAD_STATE = 2;

    /**
     * @deprecated Use AutomationState enum instead.
     */
    @Deprecated
    public static final int HISTORY_DOWNLOAD_SCAN_STATE = 3;

    /**
     * @deprecated Use AutomationState enum instead.
     */
    @Deprecated
    public static final int HISTORY_TRANSFER_STATE = 4;

    public static class Automation {
        public long telegramId;

        public long chatId;

        public PreloadConfig preload;

        public DownloadConfig download;

        public TransferConfig transfer;

        /**
         * Legacy bitwise state field.
         * Kept for backward compatibility but should be migrated to use getStates()/setStates().
         */
        public int state;
        
        /**
         * Get automation states as enum set.
         * Converts from legacy bitwise state if needed.
         */
        @JsonIgnore
        public Set<AutomationState> getStates() {
            return AutomationState.fromLegacyState(state);
        }
        
        /**
         * Set automation states from enum set.
         * Updates legacy bitwise state for backward compatibility.
         */
        @JsonIgnore
        public void setStates(Set<AutomationState> states) {
            this.state = AutomationState.toLegacyState(states);
        }
        
        /**
         * Mark a state as complete.
         */
        @JsonIgnore
        public void complete(AutomationState automationState) {
            Set<AutomationState> states = getStates();
            states.add(automationState);
            setStates(states);
        }
        
        /**
         * Check if a state is complete.
         */
        @JsonIgnore
        public boolean isComplete(AutomationState automationState) {
            return getStates().contains(automationState);
        }
        
        /**
         * Check if a state is not complete.
         */
        @JsonIgnore
        public boolean isNotComplete(AutomationState automationState) {
            return !isComplete(automationState);
        }

        public String uniqueKey() {
            return telegramId + ":" + chatId;
        }

        /**
         * @deprecated Use complete(AutomationState) instead.
         */
        @Deprecated
        @JsonIgnore
        public void complete(int bitwise) {
            MessyUtils.BitState bitState = new MessyUtils.BitState(state);
            bitState.enableState(bitwise);
            state = bitState.getState();
        }

        /**
         * @deprecated Use isComplete(AutomationState) instead.
         */
        @Deprecated
        @JsonIgnore
        public boolean isComplete(int bitwise) {
            MessyUtils.BitState bitState = new MessyUtils.BitState(state);
            return bitState.isStateEnabled(bitwise);
        }

        /**
         * @deprecated Use isNotComplete(AutomationState) instead.
         */
        @Deprecated
        @JsonIgnore
        public boolean isNotComplete(int bitwise) {
            return !isComplete(bitwise);
        }
    }

    public static class PreloadConfig {
        public boolean enabled;

        public long nextFromMessageId;

        public PreloadConfig with(PreloadConfig config) {
            this.enabled = config.enabled;
            return this;
        }
    }

    public static class DownloadConfig {
        public boolean enabled;

        public DownloadRule rule;

        public String nextFileType;

        public long nextFromMessageId;

        public DownloadConfig with(DownloadConfig config) {
            this.enabled = config.enabled;
            this.rule = config.rule;
            return this;
        }
    }

    public static class DownloadRule {
        public String query;

        public List<String> fileTypes;

        public boolean downloadHistory;

        public boolean downloadCommentFiles;

        public String filterExpr;

        /** UTC seconds since epoch. If present, only backfill messages on/after this. */
        public Integer historySince;

        /** If true, download from oldest to newest. If false (default), download from newest to oldest. */
        public boolean downloadOldestFirst;
    }

    public static class TransferConfig {
        public boolean enabled;

        public TransferRule rule;

        public TransferConfig with(TransferConfig config) {
            this.enabled = config.enabled;
            this.rule = config.rule;
            return this;
        }
    }

    public static class TransferRule {
        public boolean transferHistory;

        public String destination;

        public Transfer.TransferPolicy transferPolicy;

        public Transfer.DuplicationPolicy duplicationPolicy;

        public JsonObject extra;
    }

    public SettingAutoRecords() {
        this.automations = new ArrayList<>();
    }

    public SettingAutoRecords(List<Automation> automations) {
        this.automations = automations;
    }

    public boolean exists(long telegramId, long chatId) {
        return automations.stream().anyMatch(item -> item.telegramId == telegramId && item.chatId == chatId);
    }

    public void add(Automation item) {
        automations.removeIf(i -> i.telegramId == item.telegramId && i.chatId == item.chatId);
        automations.add(item);
    }

    public void remove(long telegramId, long chatId) {
        automations.removeIf(item -> item.telegramId == telegramId && item.chatId == chatId);
    }

    @JsonIgnore
    public List<Automation> getPreloadEnabledItems() {
        return automations.stream()
                .filter(i -> i.preload != null && i.preload.enabled)
                .toList();
    }

    @JsonIgnore
    public List<Automation> getDownloadEnabledItems() {
        return automations.stream()
                .filter(i -> i.download != null && i.download.enabled)
                .toList();
    }

    @JsonIgnore
    public List<Automation> getTransferEnabledItems() {
        return automations.stream()
                .filter(i -> i.transfer != null && i.transfer.enabled)
                .toList();
    }

    public Map<Long, Automation> getItems(long telegramId) {
        return automations.stream()
                .filter(item -> item.telegramId == telegramId)
                .collect(Collectors.toMap(i -> i.chatId, Function.identity()));
    }

    public Automation getItem(long telegramId, long chatId) {
        return automations.stream()
                .filter(item -> item.telegramId == telegramId && item.chatId == chatId)
                .findFirst()
                .orElse(null);
    }

}
