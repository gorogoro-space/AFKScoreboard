package space.gorogoro.afkscoreboard;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.geysermc.floodgate.api.FloodgateApi;

/**
 * 統合版(Floodgate)の人の判定。Floodgate が無いときは detect が null を返し、このクラスの API は呼ばれない。
 */
final class BedrockPlayers {

    private final FloodgateApi api;

    private BedrockPlayers(FloodgateApi api) {
        this.api = api;
    }

    static BedrockPlayers detect(AFKScoreboard plugin) {
        if (!Bukkit.getPluginManager().isPluginEnabled("floodgate")) {
            return null;
        }
        try {
            return new BedrockPlayers(FloodgateApi.getInstance());
        } catch (Throwable e) {
            plugin.getLogger().warning("Floodgate API を使えないため、統合版向けの名前の切り替えはしません: " + e);
            return null;
        }
    }

    boolean is(Player player) {
        return api.isFloodgatePlayer(player.getUniqueId());
    }
}
