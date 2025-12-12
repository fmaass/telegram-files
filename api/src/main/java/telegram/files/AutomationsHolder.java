package telegram.files;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.Future;
import io.vertx.core.json.Json;
import telegram.files.repository.SettingAutoRecords;
import telegram.files.repository.SettingKey;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class AutomationsHolder {
    private final Log log = LogFactory.get();

    private final SettingAutoRecords AUTO_RECORDS = new SettingAutoRecords();

    private final List<Consumer<List<SettingAutoRecords.Automation>>> onRemoveListeners = new ArrayList<>();

    private volatile boolean initialized = false;

    public static final AutomationsHolder INSTANCE = new AutomationsHolder();

    private AutomationsHolder() {
    }

    public SettingAutoRecords autoRecords() {
        return AUTO_RECORDS;
    }

    public void registerOnRemoveListener(Consumer<List<SettingAutoRecords.Automation>> onRemove) {
        onRemoveListeners.add(onRemove);
    }

    public synchronized Future<Void> init() {
        if (initialized) {
            return Future.succeededFuture();
        }
        return DataVerticle.settingRepository.<SettingAutoRecords>getByKey(SettingKey.automation)
                .onSuccess(settingAutoRecords -> {
                    initialized = true;
                    if (settingAutoRecords == null) {
                        return;
                    }
                    settingAutoRecords.automations.forEach(item -> {
                        // Validate and auto-correct control state on startup
                        item.validateControlState();
                        
                        // If enabled=true but state is STOPPED, auto-transition to IDLE
                        if (item.download != null && item.download.enabled && item.isStopped()) {
                            log.info("Auto-correcting stopped automation to IDLE for chatId: %d (enabled=true)".formatted(item.chatId));
                            item.setControlState(telegram.files.repository.AutomationControlState.IDLE);
                        }
                        
                        TelegramVerticles.get(item.telegramId)
                                .ifPresentOrElse(telegramVerticle -> {
                                    if (telegramVerticle.authorized) {
                                        AUTO_RECORDS.add(item);
                                    } else {
                                        log.warn("Init auto records fail. Telegram verticle not authorized: %s".formatted(item.telegramId));
                                    }
                                }, () -> log.warn("Init auto records fail. Telegram verticle not found: %s".formatted(item.telegramId)));
                    });
                    
                    // Save corrected states back to database
                    saveAutoRecords().onFailure(e -> log.warn("Failed to save corrected automation states: %s".formatted(e.getMessage())));
                })
                .onFailure(e -> log.error("Init auto records failed!", e))
                .mapEmpty();
    }

    public void onAutoRecordsUpdate(SettingAutoRecords records) {
        for (SettingAutoRecords.Automation automation : records.automations) {
            // Validate and auto-correct control state
            automation.validateControlState();
            
            // Sync control state with enabled flag
            if (automation.download != null && automation.download.enabled && automation.isStopped()) {
                log.info("Auto-correcting stopped automation to IDLE for chatId: %d (enabled=true)".formatted(automation.chatId));
                automation.setControlState(telegram.files.repository.AutomationControlState.IDLE);
            } else if (automation.download == null || !automation.download.enabled) {
                if (!automation.isStopped()) {
                    log.info("Auto-correcting automation to STOPPED for chatId: %d (enabled=false)".formatted(automation.chatId));
                    automation.setControlState(telegram.files.repository.AutomationControlState.STOPPED);
                }
            }
            
            if (!AUTO_RECORDS.exists(automation.telegramId, automation.chatId)) {
                // new enabled
                TelegramVerticles.get(automation.telegramId)
                        .ifPresentOrElse(telegramVerticle -> {
                            if (telegramVerticle.authorized) {
                                AUTO_RECORDS.add(automation);
                                log.info("Add auto records success: %s".formatted(automation.uniqueKey()));
                            } else {
                                log.warn("Add auto records fail. Telegram verticle not authorized: %s".formatted(automation.telegramId));
                            }
                        }, () -> log.warn("Add auto records fail. Telegram verticle not found: %s".formatted(automation.telegramId)));
            } else {
                // update fields
                SettingAutoRecords.Automation theAutomation = AUTO_RECORDS.getItem(automation.telegramId, automation.chatId);
                theAutomation.preload.with(automation.preload);
                theAutomation.download.with(automation.download);
                theAutomation.transfer.with(automation.transfer);
                // Update control state
                theAutomation.controlState = automation.controlState;
                log.info("Update auto records success: %s".formatted(automation.uniqueKey()));
            }
        }
        // remove disabled
        List<SettingAutoRecords.Automation> removedItems = new ArrayList<>();
        AUTO_RECORDS.automations.removeIf(item -> {
            if (records.exists(item.telegramId, item.chatId)) {
                return false;
            }
            removedItems.add(item);
            log.info("Remove auto records success: %s".formatted(item.uniqueKey()));
            return true;
        });
        if (CollUtil.isNotEmpty(removedItems)) {
            onRemoveListeners.forEach(listener -> listener.accept(removedItems));
        }
    }

    public Future<Void> saveAutoRecords() {
        return DataVerticle.settingRepository.<SettingAutoRecords>getByKey(SettingKey.automation)
                .compose(settingAutoRecords -> {
                    if (settingAutoRecords == null) {
                        settingAutoRecords = new SettingAutoRecords();
                    }
                    AUTO_RECORDS.automations.forEach(settingAutoRecords::add);
                    return DataVerticle.settingRepository.createOrUpdate(SettingKey.automation.name(), Json.encode(settingAutoRecords));
                })
                .onFailure(e -> log.error("Save auto records failed!", e))
                .mapEmpty();
    }
}
