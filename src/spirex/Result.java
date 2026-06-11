package spirex;

/**
 * A single crawl result. {@code tech} is populated only when -tech-detect is on;
 * {@code outOfScope} marks endpoints surfaced by -display-out-scope.
 */
public record Result(
        String url,
        String source,
        int depth,
        int status,
        String contentType,
        String tech,
        boolean outOfScope) {

    public static Result of(String url, String source, int depth, int status, String contentType) {
        return new Result(url, source, depth, status, contentType, null, false);
    }
}
