package net.primetiers.api;

import java.time.Duration;
import java.util.Optional;

/**
 * A request to the PrimeTiers API failed.
 *
 * <p>Futures returned by {@link PrimeTiersClient} fail with this exception, wrapped in a
 * {@link java.util.concurrent.CompletionException} when you call {@code join()}.
 *
 * <ul>
 *   <li>{@link #status()} is the HTTP status, or {@code 0} if no response arrived (network
 *       error, timeout, unreadable body).</li>
 *   <li>{@link #isRateLimited()}: you sent too many requests. Wait {@link #retryAfter()}.</li>
 *   <li>{@link #isServerError()}: the API had a problem (e.g. {@code 502}). Retry later.</li>
 * </ul>
 */
public class PrimeTiersException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int status;
    private final Duration retryAfter;

    public PrimeTiersException(int status, String message, Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.retryAfter = retryAfter;
    }

    /** HTTP status code, or {@code 0} if the request failed before a response arrived. */
    public int status() {
        return status;
    }

    /** How long the API asked you to wait (from {@code Retry-After}), if it said. */
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    /** {@code true} for HTTP 429. */
    public boolean isRateLimited() {
        return status == 429;
    }

    /** {@code true} for 5xx responses and for requests that got no response at all. */
    public boolean isServerError() {
        return status == 0 || status >= 500;
    }
}
