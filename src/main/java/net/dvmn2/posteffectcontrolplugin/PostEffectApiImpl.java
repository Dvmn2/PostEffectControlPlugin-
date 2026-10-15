package net.dvmn2.posteffectcontrolplugin;

import net.dvmn2.posteffectcontrolplugin.api.PostEffectApi;
import org.bukkit.entity.Player;

final class PostEffectApiImpl implements PostEffectApi {

    private final PostEffectControlPlugin plugin;

    PostEffectApiImpl(PostEffectControlPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean apply(Player player, String name, long durationMillis, ApplyOptions options) {
        EffectManager.ApplyResult result = plugin.getManager().apply(player.getUniqueId(), name,
                durationMillis < 0 ? -1L : durationMillis, options == null ? ApplyOptions.defaults() : options);
        if (result == EffectManager.ApplyResult.OK) {
            plugin.getNetwork().sync(player, false);
            return true;
        }
        return false;
    }

    @Override
    public boolean remove(Player player, String name) {
        boolean removed = plugin.getManager().remove(player.getUniqueId(), name);
        if (removed) {
            plugin.getNetwork().sync(player, false);
        }
        return removed;
    }

    @Override
    public void clear(Player player) {
        if (plugin.getManager().clear(player.getUniqueId())) {
            plugin.getNetwork().sync(player, false);
        }
    }

    @Override
    public boolean hasMod(Player player) {
        return NetworkManager.hasMod(player);
    }
}
