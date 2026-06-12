package telegram.files;

import java.util.Map;

public class FileRepositoryImplTestHelper {

    private static final Map<String, String> SORT_COLUMN_MAP = Map.of(
            "id", "id",
            "message_id", "message_id",
            "date", "date",
            "size", "size",
            "file_name", "file_name",
            "completion_date", "completion_date",
            "download_status", "download_status",
            "type", "type",
            "reaction_count", "reaction_count"
    );

    public static boolean isValidSort(String sort) {
        return SORT_COLUMN_MAP.containsKey(sort);
    }
}
