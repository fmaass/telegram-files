package telegram.files;

import telegram.files.repository.FileRepository;
import telegram.files.repository.SettingRepository;
import telegram.files.repository.StatisticRepository;
import telegram.files.repository.TelegramRepository;

/**
 * Context object that holds references to all repositories for dependency injection.
 * This allows services to be testable by injecting mock repositories.
 */
public class ServiceContext {
    
    private final FileRepository fileRepository;
    private final TelegramRepository telegramRepository;
    private final SettingRepository settingRepository;
    private final StatisticRepository statisticRepository;
    
    public ServiceContext(
            FileRepository fileRepository,
            TelegramRepository telegramRepository,
            SettingRepository settingRepository,
            StatisticRepository statisticRepository
    ) {
        this.fileRepository = fileRepository;
        this.telegramRepository = telegramRepository;
        this.settingRepository = settingRepository;
        this.statisticRepository = statisticRepository;
    }
    
    public FileRepository fileRepository() {
        return fileRepository;
    }
    
    public TelegramRepository telegramRepository() {
        return telegramRepository;
    }
    
    public SettingRepository settingRepository() {
        return settingRepository;
    }
    
    public StatisticRepository statisticRepository() {
        return statisticRepository;
    }
    
    /**
     * Get the ServiceContext instance from DataVerticle.
     * This is a convenience method for accessing the singleton instance.
     */
    public static ServiceContext fromDataVerticle() {
        return DataVerticle.serviceContext;
    }
}
