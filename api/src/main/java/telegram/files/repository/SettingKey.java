package telegram.files.repository;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.lang.Version;
import cn.hutool.core.util.StrUtil;
import io.vertx.core.json.JsonObject;

import java.util.function.Function;

public enum SettingKey {
    version(Version::new),
    uniqueOnly(Convert::toBool, false),
    imageLoadSize,
    alwaysHide(Convert::toBool, false),
    showSensitiveContent(Convert::toBool, false),
    automation(value -> StrUtil.isBlank(value) ? null : new JsonObject(value).mapTo(SettingAutoRecords.class)),
    /**
     * Auto download limit for each telegram account
     */
    autoDownloadLimit(Convert::toInt),
    autoDownloadTimeLimited(value -> StrUtil.isBlank(value) ? null : new JsonObject(value).mapTo(SettingTimeLimitedDownload.class)),
    proxys(value -> StrUtil.isBlank(value) ? null : new JsonObject(value).mapTo(SettingProxyRecords.class)),
    /**
     * Interval for calculating average speed, in seconds
     */
    avgSpeedInterval(Convert::toInt, 5 * 60),
    /**
     * Speed units for displaying download speed, e.g., "bits" or "bytes"
     */
    speedUnits(Function.identity(), "bits"),
    /**
     * Track downloaded state persistently - when enabled, files stay in "downloaded" state
     * until moved/deleted. When disabled, files show as "completed" immediately.
     */
    trackDownloadedState(Convert::toBool, false),
    tags(value -> StrUtil.isBlank(value) ? null : StrUtil.split(value, ","));

    public final Function<String, ?> converter;

    public final Object defaultValue;

    SettingKey() {
        this(Function.identity(), null);
    }

    SettingKey(Function<String, ?> converter) {
        this(converter, null);
    }

    SettingKey(Function<String, ?> converter, Object defaultValue) {
        this.converter = converter;
        this.defaultValue = defaultValue;
    }
}
