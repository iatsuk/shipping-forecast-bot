package boats.log.shippingforecast.telegram;

import boats.log.shippingforecast.BuildInfo;
import boats.log.shippingforecast.forecast.ForecastCacheRepository;
import boats.log.shippingforecast.forecast.ForecastProvider;
import boats.log.shippingforecast.subscription.SubscriptionRepository;
import boats.log.shippingforecast.user.UserRepository;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Handles bot admin commands. Only responds to the configured admin chat ID.
 *
 * <p>Not a Spring component — constructed directly by {@link TelegramBot}.
 *
 * <p>Supported commands:
 * <ul>
 *   <li>{@code /status} — statistics, system health, and build info</li>
 * </ul>
 */
class AdminCommandHandler {

    private final BotInteraction bot;
    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final List<ForecastProvider> providers;
    private final ForecastCacheRepository cacheRepository;
    private final BuildInfo buildInfo;

    AdminCommandHandler(
            BotInteraction bot,
            UserRepository userRepository,
            SubscriptionRepository subscriptionRepository,
            List<ForecastProvider> providers,
            ForecastCacheRepository cacheRepository,
            BuildInfo buildInfo) {
        this.bot = bot;
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.providers = providers;
        this.cacheRepository = cacheRepository;
        this.buildInfo = buildInfo;
    }

    void handleStatus(long chatId) {
        bot.send(chatId, buildStatusMessage());
    }

    private String buildStatusMessage() {
        int userCount = userRepository.count();
        int subscriptionCount = subscriptionRepository.count();

        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMb = rt.maxMemory() / (1024 * 1024);

        double loadAvg = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        String loadStr = loadAvg >= 0 ? String.format("%.2f", loadAvg) : "n/a";

        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        String uptimeStr = formatUptime(uptimeMs);

        return "📊 Bot Status\n\n"
                + "👥 Users: " + userCount + "\n"
                + "📋 Subscriptions: " + subscriptionCount + "\n\n"
                + "🕐 Server time: " + Instant.now() + "\n"
                + "⏱ Uptime: " + uptimeStr + "\n"
                + "💾 Memory: " + usedMb + " / " + maxMb + " MB\n"
                + "⚡ Load avg: " + loadStr + "\n\n"
                + buildProviderFetchSection()
                + "🏷 Commit: " + buildInfo.commitHash() + "\n"
                + "🔨 Built: " + buildInfo.buildTime();
    }

    private String buildProviderFetchSection() {
        StringBuilder sb = new StringBuilder("📡 Last fetch\n");
        for (ForecastProvider provider : providers) {
            String fetchedAt = cacheRepository.findByUrl(provider.url())
                    .map(cache -> cache.fetchedAt().toString())
                    .orElse("never");
            sb.append("  • ").append(provider.name()).append(": ").append(fetchedAt).append("\n");
        }
        return sb.append("\n").toString();
    }

    private static String formatUptime(long ms) {
        long days = TimeUnit.MILLISECONDS.toDays(ms);
        long hours = TimeUnit.MILLISECONDS.toHours(ms) % 24;
        long minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60;
        if (days > 0) {
            return days + "d " + hours + "h " + minutes + "m";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }
}
