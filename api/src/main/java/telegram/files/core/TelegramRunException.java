package telegram.files.core;

import org.drinkless.tdlib.TdApi;

import java.util.StringJoiner;

public class TelegramRunException extends RuntimeException {
    private final TdApi.Error error;

    public TelegramRunException(TdApi.Error error) {
        super(new StringJoiner(", ")
                .add("code: " + error.code)
                .add("message: " + error.message)
                .toString());
        this.error = error;
    }

    public TdApi.Error getError() {
        return error;
    }

}
