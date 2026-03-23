package boats.log.shippingforecast;

/**
 * Immutable build metadata embedded at compile time via Maven resource filtering
 * (see {@code build.properties}). Both fields fall back to {@code "unknown"} when
 * the git plugin cannot determine the value (e.g. a source export without a .git directory).
 */
public record BuildInfo(String commitHash, String buildTime) {}
