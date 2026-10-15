package net.dvmn2.posteffectcontrolplugin;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.dvmn2.posteffectcontrolplugin.api.PostEffectApi;
import net.dvmn2.posteffectcontrolplugin.commands.PostEffectCommand;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class PostEffectControlPlugin extends JavaPlugin {

    public static final String ADMIN_PERMISSION = "posteffect.admin";
    /** Игроки с этим правом не кикаются за отсутствие мода (если включён require-client-mod). */
    public static final String BYPASS_PERMISSION = "posteffect.bypass";

    private EffectLibrary library;
    private EffectManager manager;
    private NetworkManager network;
    private ResourceBridge bridge;

    @Override
    public void onEnable() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        saveDefaultConfig();
        Lang.setLanguage(getConfig().getString("settings.language", "auto"));

        library = new EffectLibrary(this);
        List<String> warnings = library.load();
        warnings.forEach(w -> getLogger().warning(w));

        manager = new EffectManager(this);
        manager.load();

        bridge = new ResourceBridge(this);
        bridge.reload().forEach(w -> getLogger().warning(w));

        network = new NetworkManager(this);
        getServer().getPluginManager().registerEvents(network, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, NetworkManager.SYNC_CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, NetworkManager.STATUS_CHANNEL, network);
        getServer().getMessenger().registerIncomingPluginChannel(this, NetworkManager.CAPS_CHANNEL, network);

        getServer().getServicesManager().register(PostEffectApi.class, new PostEffectApiImpl(this), this, ServicePriority.Normal);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(
                        new PostEffectCommand(this).build().build(),
                        "Управление постэффектами отдельных игроков (клиентский мод PostEffectControl)",
                        List.of("postfx")
                ));

        // Истечение эффектов: дважды в секунду. Клиент гасит эффект сам по своему таймеру,
        // это нужно, чтобы убрать запись и отправить актуальный набор.
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            Set<UUID> changed = manager.expire(System.currentTimeMillis());
            for (UUID uuid : changed) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    network.sync(player, false);
                }
            }
        }, 10L, 10L);
        Bukkit.getScheduler().runTaskTimer(this, () -> manager.saveIfDirty(), 600L, 600L);
        // Интеграция с ResourceControlPlugin: выдача/продление/снятие паков под активные эффекты.
        Bukkit.getScheduler().runTaskTimer(this, () -> bridge.tick(), 100L, 100L);

        getLogger().info("PostEffectControlPlugin включён. Встроенных эффектов: " + library.builtinCount()
                + ", привязок к ресурспакам: " + bridge.bindingCount() + ", пресетов: " + library.presetIds().size()
                + ". Команда: /posteffect give|remove|clear|get|list|info|reload");
    }

    /** /posteffect reload. @return предупреждения. */
    public List<String> reloadAll() {
        reloadConfig();
        Lang.setLanguage(getConfig().getString("settings.language", "auto"));
        List<String> warnings = new ArrayList<>(library.load());
        warnings.addAll(bridge.reload());
        manager.load();
        network.syncAll(true);
        return warnings;
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.save();
        }
    }

    public EffectLibrary getLibrary() {
        return library;
    }

    public EffectManager getManager() {
        return manager;
    }

    public ResourceBridge getBridge() {
        return bridge;
    }

    public NetworkManager getNetwork() {
        return network;
    }
}
