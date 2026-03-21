package boats.log.shippingforecast.telegram;

import boats.log.shippingforecast.forecast.ForecastCache;
import boats.log.shippingforecast.forecast.ForecastCacheRepository;
import boats.log.shippingforecast.forecast.ForecastProvider;
import boats.log.shippingforecast.forecast.GeoLocation;
import boats.log.shippingforecast.forecast.ImageCache;
import boats.log.shippingforecast.forecast.ImageCacheRepository;
import boats.log.shippingforecast.subscription.SubscriptionRepository;
import boats.log.shippingforecast.user.TelegramUser;
import boats.log.shippingforecast.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Handles all bot commands and inline keyboard interactions.
 *
 * <p>Not a Spring component — constructed directly by {@link TelegramBot} which passes
 * itself as the {@link BotInteraction}. This avoids a circular Spring dependency
 * (TelegramBot ← BotCommandHandler ← BotInteraction = TelegramBot).
 *
 * <p>Navigation flow:
 * <ol>
 *   <li>/start → greeting + provider list keyboard</li>
 *   <li>Provider selected → send area map image + area keyboard</li>
 *   <li>Area selected → send the latest forecast + offer a subscribe/unsubscribe button</li>
 *   <li>Subscribe/Unsubscribe clicked → update subscription + return to a provider list</li>
 *   <li>/stop → delete all user data + goodbye</li>
 * </ol>
 */
class BotCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(BotCommandHandler.class);

    private static final String PROVIDER_PREFIX = "provider:";
    private static final String AREA_PREFIX = "area:";
    private static final String SUBSCRIBE_PREFIX = "subscribe:";
    private static final String UNSUBSCRIBE_PREFIX = "unsubscribe:";
    private static final String MY_SUBSCRIPTIONS = "my_subscriptions";

    private static final int LOCATION_SUGGESTIONS = 3;

    private final BotInteraction bot;
    private final ForecastCacheRepository cacheRepository;
    private final ImageCacheRepository imageCacheRepository;
    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;

    private final List<ForecastProvider> providers;
    /** Provider keyboard built once at startup — does not change at runtime. */
    private final List<List<MenuOption>> providerKeyboard;
    private final Map<String, ForecastProvider> providersByName;
    private final Map<String, GeoLocation> geoLocationsByName;

    BotCommandHandler(
            BotInteraction bot,
            List<ForecastProvider> providers,
            ForecastCacheRepository cacheRepository,
            ImageCacheRepository imageCacheRepository,
            UserRepository userRepository,
            SubscriptionRepository subscriptionRepository
    ) {
        this.bot = bot;
        this.providers = providers;
        this.cacheRepository = cacheRepository;
        this.imageCacheRepository = imageCacheRepository;
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.providerKeyboard = buildProviderKeyboard(providers);
        this.providersByName = buildProvidersByNameMap(providers);
        this.geoLocationsByName = buildGeoLocationsByNameMap(providers);
    }

    void handleStart(long chatId, String firstName) {
        userRepository.register(new TelegramUser(chatId));

        List<String> subscriptions = subscriptionRepository.findAreasByChatId(chatId);

        List<List<MenuOption>> keyboard = new ArrayList<>();
        if (!subscriptions.isEmpty()) {
            keyboard.add(List.of(new MenuOption("Active subscriptions (" + subscriptions.size() + ")", MY_SUBSCRIPTIONS)));
        }
        keyboard.addAll(providerKeyboard);

        bot.sendMenu(chatId,
                "Welcome, " + firstName + "! I deliver shipping weather forecasts.\n\n"
                + "📍 Share your location and I'll suggest the closest forecast areas.\n\n"
                + "Or select a forecast provider:",
                keyboard);
    }

    void handleLocation(long chatId, double latitude, double longitude) {
        List<Map.Entry<GeoLocation, Double>> closest = geoLocationsByName.values().stream()
                .map(loc -> Map.entry(loc, haversineNm(latitude, longitude, loc.latitude(), loc.longitude())))
                .sorted(Map.Entry.comparingByValue())
                .limit(LOCATION_SUGGESTIONS)
                .toList();

        if (closest.isEmpty()) {
            bot.send(chatId, "No forecast areas available.");
            return;
        }

        StringBuilder text = new StringBuilder("Closest forecast areas to your location:\n\n");
        for (Map.Entry<GeoLocation, Double> entry : closest) {
            text.append("• ").append(entry.getKey().name())
                    .append(" — ").append(Math.round(entry.getValue())).append(" nm\n");
        }
        text.append("\nSelect an area to subscribe:");

        List<List<MenuOption>> keyboard = closest.stream()
                .map(e -> List.of(new MenuOption(e.getKey().name(), AREA_PREFIX + e.getKey().name())))
                .toList();

        bot.sendMenu(chatId, text.toString(), keyboard);
    }

    void handleStop(long chatId) {
        userRepository.delete(chatId);
        bot.send(chatId,
                "You have been unregistered. All your data has been deleted.\n\n"
                + "Send /start to use the bot again.");
    }

    void handleCallback(long chatId, String callbackQueryId, String data) {
        // Acknowledge the query first to dismiss the button's loading indicator.
        bot.answerCallbackQuery(callbackQueryId);

        if (data.startsWith(PROVIDER_PREFIX)) {
            String providerName = data.substring(PROVIDER_PREFIX.length());
            ForecastProvider provider = providersByName.get(providerName.toLowerCase(Locale.ROOT));
            if (provider != null) {
                handleProviderSelected(chatId, provider);
            }
        } else if (data.startsWith(AREA_PREFIX)) {
            String rest = data.substring(AREA_PREFIX.length());
            int slash = rest.indexOf('/');
            if (slash >= 0) {
                // Callback from a provider's area keyboard — provider context is known.
                String providerName = rest.substring(0, slash);
                String areaName = rest.substring(slash + 1);
                ForecastProvider provider = providersByName.get(providerName.toLowerCase(Locale.ROOT));
                handleAreaSelected(chatId, areaName, provider);
            } else {
                // Callback from the subscriptions list or location suggestions — no provider context.
                handleAreaSelected(chatId, rest, null);
            }
        } else if (data.startsWith(SUBSCRIBE_PREFIX)) {
            String areaName = data.substring(SUBSCRIBE_PREFIX.length());
            handleSubscribeArea(chatId, areaName);
        } else if (data.startsWith(UNSUBSCRIBE_PREFIX)) {
            String areaName = data.substring(UNSUBSCRIBE_PREFIX.length());
            handleUnsubscribeArea(chatId, areaName);
        } else if (MY_SUBSCRIPTIONS.equals(data)) {
            handleMySubscriptions(chatId);
        }
    }

    private void handleProviderSelected(long chatId, ForecastProvider provider) {
        List<List<MenuOption>> areaKeyboard = buildAreaKeyboard(provider);
        String caption = "<b><a href=\"" + provider.url() + "\">" + provider.name() + "</a></b>" + "\n\n"
                + provider.description() + "\n\n"
                + "Select an area to subscribe to automatic updates:";

        // If the provider has a map image, show it with the area keyboard.
        Optional<String> mapUrl = provider.mapImageUrl();
        if (mapUrl.isPresent()) {
            Optional<ImageCache> cachedImage = imageCacheRepository.findByUrl(mapUrl.get());
            if (cachedImage.isPresent()) {
                bot.sendPhoto(chatId, cachedImage.get().data(), caption, areaKeyboard);
                return;
            }
        }

        // Fallback when no image is cached: show a plain text menu.
        bot.sendMenu(chatId, caption, areaKeyboard);
    }

    /**
     * Shows the latest forecast for {@code areaName} and offers a subscribe/unsubscribe button.
     *
     * @param provider the provider whose forecast to show, or {@code null} when the provider is
     *                 not known (subscriptions list, location suggestions) — in that case all
     *                 providers covering this area send their forecast
     */
    private void handleAreaSelected(long chatId, String areaName, ForecastProvider provider) {
        GeoLocation location = geoLocationsByName.get(areaName.toLowerCase(Locale.ROOT));
        if (location == null) {
            bot.sendMenu(chatId, "Unknown area. Please select from the menu:", providerKeyboard);
            return;
        }

        if (provider != null) {
            sendForecastForArea(chatId, provider, areaName);
        } else {
            // No provider context: send it from every provider that covers this area.
            providers.stream()
                    .filter(p -> p.geoLocations().stream()
                            .anyMatch(loc -> loc.name().equalsIgnoreCase(areaName)))
                    .forEach(p -> sendForecastForArea(chatId, p, areaName));
        }

        // Ensure the user exists before checking subscriptions (FK constraint).
        userRepository.register(new TelegramUser(chatId));

        boolean isSubscribed = subscriptionRepository.findAreasByChatId(chatId)
                .stream().anyMatch(a -> a.equalsIgnoreCase(areaName));

        if (isSubscribed) {
            bot.sendMenu(chatId,
                    "You are subscribed to " + location.name() + ".",
                    List.of(List.of(new MenuOption("Unsubscribe from " + location.name(), UNSUBSCRIBE_PREFIX + location.name()))));
        } else {
            bot.sendMenu(chatId,
                    "Subscribe to " + location.name() + " for automatic forecast updates?",
                    List.of(List.of(new MenuOption("Subscribe to " + location.name(), SUBSCRIBE_PREFIX + location.name()))));
        }
    }

    private void sendForecastForArea(long chatId, ForecastProvider provider, String areaName) {
        Optional<ForecastCache> cached = cacheRepository.findByUrl(provider.url());
        cached.flatMap(forecastCache -> provider.parse(forecastCache.content()).stream()
                .filter(f -> f.location().name().equalsIgnoreCase(areaName))
                .filter(f -> !f.text().isBlank())
                .findFirst()).ifPresent(f -> bot.send(chatId, f.text()));
    }

    private void handleSubscribeArea(long chatId, String areaName) {
        GeoLocation location = geoLocationsByName.get(areaName.toLowerCase(Locale.ROOT));
        if (location == null) {
            bot.sendMenu(chatId, "Unknown area. Please select from the menu:", providerKeyboard);
            return;
        }

        userRepository.register(new TelegramUser(chatId));
        subscriptionRepository.subscribe(chatId, location.name());
        log.info("User {} subscribed to area '{}'", chatId, location.name());
        bot.sendMenu(chatId,
                "Subscribed to " + location.name() + ". You will receive the next forecast automatically.\n\n"
                + "Select a forecast provider:",
                providerKeyboard);
    }

    private void handleMySubscriptions(long chatId) {
        List<String> subscriptions = subscriptionRepository.findAreasByChatId(chatId);
        if (subscriptions.isEmpty()) {
            bot.sendMenu(chatId, "You have no active subscriptions.\n\nSelect a forecast provider:", providerKeyboard);
            return;
        }

        List<List<MenuOption>> keyboard = subscriptions.stream()
                .map(area -> List.of(new MenuOption(area, AREA_PREFIX + area)))
                .toList();
        bot.sendMenu(chatId, "Your active subscriptions — tap an area to view the latest forecast or unsubscribe:", keyboard);
    }

    private void handleUnsubscribeArea(long chatId, String areaName) {
        GeoLocation location = geoLocationsByName.get(areaName.toLowerCase(Locale.ROOT));
        if (location == null) {
            bot.sendMenu(chatId, "Unknown area. Please select from the menu:", providerKeyboard);
            return;
        }

        subscriptionRepository.unsubscribe(chatId, location.name());
        log.info("User {} unsubscribed from area '{}'", chatId, location.name());
        bot.sendMenu(chatId,
                "Unsubscribed from " + location.name() + ".\n\nSelect a forecast provider:",
                providerKeyboard);
    }

    // --- keyboard builders ---

    private static List<List<MenuOption>> buildProviderKeyboard(List<ForecastProvider> providers) {
        // One provider per row.
        return providers.stream()
                .map(p -> List.of(new MenuOption(p.name(), PROVIDER_PREFIX + p.name())))
                .toList();
    }

    private static List<List<MenuOption>> buildAreaKeyboard(ForecastProvider provider) {
        // Two areas per row for a compact layout (9 DWD areas → 4 full rows + 1 last row).
        // The provider name is embedded in the callback data, so the handler knows which
        // provider's forecast to show, even when multiple providers share an area name.
        List<GeoLocation> locations = provider.geoLocations();
        List<List<MenuOption>> rows = new ArrayList<>();
        for (int i = 0; i < locations.size(); i += 2) {
            List<MenuOption> row = new ArrayList<>();
            row.add(new MenuOption(locations.get(i).name(), areaCallback(provider, locations.get(i))));
            if (i + 1 < locations.size()) {
                row.add(new MenuOption(locations.get(i + 1).name(), areaCallback(provider, locations.get(i + 1))));
            }
            rows.add(row);
        }
        return rows;
    }

    private static String areaCallback(ForecastProvider provider, GeoLocation location) {
        return AREA_PREFIX + provider.name() + "/" + location.name();
    }

    private static Map<String, ForecastProvider> buildProvidersByNameMap(List<ForecastProvider> providers) {
        Map<String, ForecastProvider> map = new HashMap<>();
        for (ForecastProvider p : providers) {
            map.put(p.name().toLowerCase(Locale.ROOT), p);
        }
        return Map.copyOf(map);
    }

    private static Map<String, GeoLocation> buildGeoLocationsByNameMap(List<ForecastProvider> providers) {
        Map<String, GeoLocation> map = new HashMap<>();
        for (ForecastProvider p : providers) {
            for (GeoLocation loc : p.geoLocations()) {
                map.put(loc.name().toLowerCase(Locale.ROOT), loc);
            }
        }
        return Map.copyOf(map);
    }

    /** Returns the great-circle distance in nautical miles between two WGS-84 points. */
    private static double haversineNm(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371.0;
        final double nm = 1.852;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a)) / nm;
    }
}
