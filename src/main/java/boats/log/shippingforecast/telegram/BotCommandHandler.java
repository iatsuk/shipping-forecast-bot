package boats.log.shippingforecast.telegram;

import boats.log.shippingforecast.forecast.ForecastCache;
import boats.log.shippingforecast.forecast.ForecastCacheRepository;
import boats.log.shippingforecast.forecast.ForecastProvider;
import boats.log.shippingforecast.forecast.GeoLocation;
import boats.log.shippingforecast.forecast.ShippingForecast;
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
 *   <li>Provider selected → send latest forecasts + area keyboard</li>
 *   <li>Area selected → subscribe user + return to provider list</li>
 *   <li>/stop → delete all user data + goodbye</li>
 * </ol>
 */
class BotCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(BotCommandHandler.class);

    private static final String PROVIDER_PREFIX = "provider:";
    private static final String AREA_PREFIX = "area:";

    private final BotInteraction bot;
    private final ForecastCacheRepository cacheRepository;
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
            UserRepository userRepository,
            SubscriptionRepository subscriptionRepository
    ) {
        this.bot = bot;
        this.providers = providers;
        this.cacheRepository = cacheRepository;
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.providerKeyboard = buildProviderKeyboard(providers);
        this.providersByName = buildProviderMap(providers);
        this.geoLocationsByName = buildGeoLocationMap(providers);
    }

    void handleStart(long chatId, String firstName) {
        userRepository.register(new TelegramUser(chatId));
        bot.sendMenu(chatId,
                "Welcome, " + firstName + "! I deliver shipping weather forecasts.\n\n"
                + "Select a forecast provider:",
                providerKeyboard);
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
            String areaName = data.substring(AREA_PREFIX.length());
            handleAreaSelected(chatId, areaName);
        }
    }

    private void handleProviderSelected(long chatId, ForecastProvider provider) {
        Optional<ForecastCache> cached = cacheRepository.findByUrl(provider.url());
        if (cached.isEmpty()) {
            bot.sendMenu(chatId,
                    "No forecast available yet for " + provider.name() + ". Please try again later.",
                    providerKeyboard);
            return;
        }

        List<ShippingForecast> forecasts = provider.parse(cached.get().content());
        bot.send(chatId, "Latest forecast from " + provider.name() + ":");
        for (ShippingForecast forecast : forecasts) {
            if (!forecast.text().isBlank()) {
                bot.send(chatId, forecast.text());
            }
        }

        bot.sendMenu(chatId, "Select an area to subscribe to automatic updates:",
                buildAreaKeyboard(provider));
    }

    private void handleAreaSelected(long chatId, String areaName) {
        GeoLocation location = geoLocationsByName.get(areaName.toLowerCase(Locale.ROOT));
        if (location == null) {
            bot.sendMenu(chatId, "Unknown area. Please select from the menu:", providerKeyboard);
            return;
        }
        // Register before subscribing — the FK on user_subscription requires the user to exist.
        userRepository.register(new TelegramUser(chatId));
        subscriptionRepository.subscribe(chatId, location.name());
        log.info("User {} subscribed to area '{}'", chatId, location.name());
        bot.sendMenu(chatId,
                "Subscribed to " + location.name() + ". You will receive the next forecast automatically.\n\n"
                + "Select a forecast provider:",
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
        List<GeoLocation> locations = provider.geoLocations();
        List<List<MenuOption>> rows = new ArrayList<>();
        for (int i = 0; i < locations.size(); i += 2) {
            List<MenuOption> row = new ArrayList<>();
            row.add(new MenuOption(locations.get(i).name(), AREA_PREFIX + locations.get(i).name()));
            if (i + 1 < locations.size()) {
                row.add(new MenuOption(locations.get(i + 1).name(), AREA_PREFIX + locations.get(i + 1).name()));
            }
            rows.add(row);
        }
        return rows;
    }

    private static Map<String, ForecastProvider> buildProviderMap(List<ForecastProvider> providers) {
        Map<String, ForecastProvider> map = new HashMap<>();
        for (ForecastProvider p : providers) {
            map.put(p.name().toLowerCase(Locale.ROOT), p);
        }
        return Map.copyOf(map);
    }

    private static Map<String, GeoLocation> buildGeoLocationMap(List<ForecastProvider> providers) {
        Map<String, GeoLocation> map = new HashMap<>();
        for (ForecastProvider p : providers) {
            for (GeoLocation loc : p.geoLocations()) {
                map.put(loc.name().toLowerCase(Locale.ROOT), loc);
            }
        }
        return Map.copyOf(map);
    }
}
