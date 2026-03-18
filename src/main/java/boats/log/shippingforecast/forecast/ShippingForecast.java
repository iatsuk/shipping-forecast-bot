package boats.log.shippingforecast.forecast;

/**
 * A parsed shipping forecast for one geographic area.
 *
 * @param location the area this forecast applies to
 * @param text     the raw forecast text as issued by the provider
 */
public record ShippingForecast(GeoLocation location, String text) {
}
