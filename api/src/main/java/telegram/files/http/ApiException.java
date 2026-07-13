package telegram.files.http;

/**
 * A boundary error carrying the HTTP status the API layer should return. Thrown/failed by the
 * resource handlers (and the Phase-2/Phase-3 boundary guards) so the router's failure handler maps a
 * specific, meaningful status instead of a blanket 400/500.
 * <ul>
 *   <li><b>404</b> — the addressed resource (account / file) does not exist.</li>
 *   <li><b>409</b> — the requested transition is illegal for the file's current state
 *       ({@code canTransitionTo}), or the file is externally-owned ({@code processed}/{@code imported})
 *       and a destructive op was requested. The response body reports the real current state.</li>
 * </ul>
 */
public class ApiException extends RuntimeException {

    private final int statusCode;

    public ApiException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public static ApiException notFound(String message) {
        return new ApiException(404, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(409, message);
    }
}
