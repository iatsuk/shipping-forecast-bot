package boats.log.shippingforecast.forecast;

/**
 * A named geographic point identifying a forecast area.
 *
 * @param name      human-readable area name (e.g. "Viking", "Rockall")
 * @param latitude  WGS-84 latitude in decimal degrees
 * @param longitude WGS-84 longitude in decimal degrees
 */
public record GeoLocation(String name, double latitude, double longitude) {
}
