package net.dvmn2.posteffectcontrolplugin;

import net.dvmn2.posteffectcontrolplugin.EffectDef.ParamDef;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Отправка эффектов клиентскому моду, приём списка доступных эффектов и статуса, кик без мода.
 * <pre>
 * S2C dvmn2:pe_sync    — ПОЛНЫЙ набор слоёв игрока, отсортированный по приоритету (каждое сообщение заменяет предыдущее)
 * C2S dvmn2:pe_status  — статус применения
 * C2S dvmn2:pe_caps    — эффекты из ресурспаков, доступные клиенту (с описанием параметров)
 * </pre>
 * Шейдеры по сети не передаются: эффект из ресурспака ({@code ns:path}) должен уже лежать в ресурсах клиента.
 */
public final class NetworkManager implements Listener, PluginMessageListener {

    public static final String SYNC_CHANNEL = "dvmn2:pe_sync";
    public static final String STATUS_CHANNEL = "dvmn2:pe_status";
    public static final String CAPS_CHANNEL = "dvmn2:pe_caps";
    public static final int PROTOCOL = 3;

    public static final int STATE_WORKING = 0;
    public static final int STATE_READY = 1;
    public static final int STATE_FAILED = 2;

    private static final int MAX_CAPS = 48;
    private static final Pattern PARAM_NAME = Pattern.compile("[a-z0-9_]{1,24}");

    public record ClientStatus(int state, String message, boolean iris, boolean rendering, int activeLayers) {
    }

    private final PostEffectControlPlugin plugin;
    private final Map<UUID, String> lastSent = new HashMap<>();
    private final Map<UUID, ClientStatus> statuses = new HashMap<>();
    /** Эффекты из ресурспаков, о которых сообщил клиент. Данные клиента: проверяются при приёме. */
    private final Map<UUID, Map<String, EffectDef>> caps = new HashMap<>();

    public NetworkManager(PostEffectControlPlugin plugin) {
        this.plugin = plugin;
    }

    /** Мод слушает канал pe_sync. */
    public static boolean hasMod(Player player) {
        return player.getListeningPluginChannels().contains(SYNC_CHANNEL);
    }

    public ClientStatus status(UUID uuid) {
        return statuses.get(uuid);
    }

    // ------------------------------------------------------------------
    // Эффекты из ресурспаков (по данным клиентов)
    // ------------------------------------------------------------------

    /** Список эффектов клиента; null — клиент ещё ничего не присылал. */
    public Map<String, EffectDef> caps(UUID uuid) {
        return caps.get(uuid);
    }

    /** Описание эффекта для конкретного игрока: встроенный или (для ns:path) из списка клиента. */
    public EffectDef effectFor(UUID uuid, String id) {
        if (EffectLibrary.isCustomId(id)) {
            Map<String, EffectDef> map = caps.get(uuid);
            return map == null ? null : map.get(id);
        }
        return plugin.getLibrary().effect(id);
    }

    /** Описание эффекта из ресурспаков у кого-нибудь из игроков онлайн (для подсказок и /posteffect info). */
    public EffectDef anyCustom(String id) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Map<String, EffectDef> map = caps.get(player.getUniqueId());
            if (map != null && map.containsKey(id)) {
                return map.get(id);
            }
        }
        return null;
    }

    /** Все эффекты из ресурспаков, о которых сообщили клиенты онлайн. */
    public List<String> knownCustomIds() {
        Set<String> ids = new TreeSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Map<String, EffectDef> map = caps.get(player.getUniqueId());
            if (map != null) {
                ids.addAll(map.keySet());
            }
        }
        return List.copyOf(ids);
    }

    // ------------------------------------------------------------------
    // Отправка набора
    // ------------------------------------------------------------------

    public void sync(Player player, boolean force) {
        if (!hasMod(player)) {
            return;
        }
        plugin.getBridge().ensure(player);

        long now = System.currentTimeMillis();
        boolean allowPack = plugin.getConfig().getBoolean("settings.allow-pack-effects", true);
        UUID uuid = player.getUniqueId();

        List<Applied> applied = new ArrayList<>(plugin.getManager().get(uuid));
        applied.removeIf(a -> a.expired(now));

        record Flat(String key, Applied.Layer layer, float[] params, Applied owner, int priority) {
        }
        List<Flat> flat = new ArrayList<>();
        StringBuilder signature = new StringBuilder();
        for (Applied a : applied) {
            signature.append(a.signature());
            for (int i = 0; i < a.layers().size(); i++) {
                Applied.Layer layer = a.layers().get(i);
                boolean custom = EffectLibrary.isCustomId(layer.effectId());
                if (custom && !allowPack) {
                    continue;
                }
                // Эффект из ресурспака отправляем, только когда клиент сообщил, что он у него есть.
                EffectDef def = effectFor(uuid, layer.effectId());
                if (def == null) {
                    continue;
                }
                float[] params = layer.params().clone();
                if (custom) {
                    for (Map.Entry<String, Float> e : layer.named().entrySet()) {
                        ParamDef pd = def.param(e.getKey());
                        if (pd != null) {
                            params[pd.index()] = e.getValue();
                        }
                    }
                    for (int n = 0; n < params.length; n++) {
                        ParamDef pd = def.paramByIndex(n);
                        if (pd != null && !Float.isNaN(params[n])) {
                            params[n] = Math.max(pd.min(), Math.min(pd.max(), params[n]));
                        }
                    }
                }
                String key = a.source() + "#" + i;
                int priority = plugin.getLibrary().priority(layer.effectId());
                signature.append('>').append(key).append('@').append(priority);
                flat.add(new Flat(key, layer, params, a, priority));
            }
        }
        // Порядок слоёв: по приоритету (меньший рисуется раньше, больший — выше). Сортировка устойчивая,
        // поэтому при равном приоритете остаётся порядок выдачи: позже выданный выше.
        flat.sort(Comparator.comparingInt(Flat::priority));
        String sig = signature.toString();
        String previous = lastSent.get(uuid);
        if (!force && sig.equals(previous)) {
            return;
        }
        if (flat.isEmpty() && previous == null) {
            return; // нечего очищать
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        try {
            Wire.writeVarInt(out, PROTOCOL);
            Wire.writeVarInt(out, flat.size());
            for (Flat f : flat) {
                Wire.writeString(out, f.key());
                Wire.writeString(out, f.layer().effectId());
                out.writeInt(f.priority());
                out.writeFloat(EffectLibrary.clamp01(f.layer().intensity() * f.owner().intensity()));
                out.writeInt(f.layer().color() < 0 ? Applied.COLOR_UNSET : f.layer().color() & 0xFFFFFF);
                for (int i = 0; i < EffectDef.PARAM_COUNT; i++) {
                    out.writeFloat(f.params()[i]);
                }
                boolean infinite = f.owner().infinite();
                out.writeBoolean(infinite);
                Wire.writeVarLong(out, infinite ? 0L : Math.max(0L, f.owner().endMillis() - now));
                Wire.writeVarInt(out, f.owner().fadeInMs());
                Wire.writeVarInt(out, f.owner().fadeOutMs());
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("Не удалось сформировать pe_sync: " + ex.getMessage());
            return;
        }

        try {
            player.sendPluginMessage(plugin, SYNC_CHANNEL, bytes.toByteArray());
            lastSent.put(uuid, sig);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Не удалось отправить pe_sync игроку " + player.getName() + ": " + ex.getMessage());
        }
    }

    public void syncAll(boolean force) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            sync(player, force);
        }
    }

    // ------------------------------------------------------------------
    // Приём от мода
    // ------------------------------------------------------------------

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(message))) {
            if (STATUS_CHANNEL.equals(channel)) {
                handleStatus(player, in);
            } else if (CAPS_CHANNEL.equals(channel)) {
                handleCaps(player, in);
            }
        } catch (IOException ex) {
            plugin.getLogger().fine("Некорректное сообщение " + channel + " от " + player.getName() + ": " + ex.getMessage());
        }
    }

    private void handleStatus(Player player, DataInputStream in) throws IOException {
        if (Wire.readVarInt(in) != PROTOCOL) {
            return;
        }
        int state = Wire.readVarInt(in);
        String text = Wire.readString(in, 2048);
        boolean iris = in.readBoolean();
        boolean rendering = in.readBoolean();
        int active = Wire.readVarInt(in);
        ClientStatus previous = statuses.put(player.getUniqueId(), new ClientStatus(state, text, iris, rendering, active));
        if (state == STATE_FAILED && (previous == null || previous.state() != STATE_FAILED || !previous.message().equals(text))) {
            plugin.getLogger().warning("Клиент " + player.getName() + " не смог применить эффект: " + text);
            for (Player admin : Bukkit.getOnlinePlayers()) {
                if (admin.hasPermission(PostEffectControlPlugin.ADMIN_PERMISSION)) {
                    admin.sendMessage(Component.text(
                            Lang.get(Lang.Key.ADMIN_CLIENT_FAILED, admin, player.getName(), text), NamedTextColor.RED));
                }
            }
        }
    }

    /** Список эффектов из ресурспаков. Всё, что пришло, считается недоверенным и проверяется. */
    private void handleCaps(Player player, DataInputStream in) throws IOException {
        if (Wire.readVarInt(in) != PROTOCOL) {
            return;
        }
        int count = Wire.readVarInt(in);
        if (count < 0 || count > MAX_CAPS) {
            return;
        }
        Map<String, EffectDef> map = new LinkedHashMap<>();
        for (int n = 0; n < count; n++) {
            String id = Wire.readString(in, 256);
            String displayName = Wire.readString(in, 512);
            boolean flashing = in.readBoolean();
            boolean overHud = in.readBoolean();
            float intensity = in.readFloat();
            int color = in.readInt();
            int paramCount = Wire.readVarInt(in);
            if (paramCount < 0 || paramCount > EffectDef.PARAM_COUNT) {
                return;
            }
            List<ParamDef> params = new ArrayList<>();
            Set<Integer> usedIndexes = new HashSet<>();
            Set<String> usedNames = new HashSet<>();
            for (int i = 0; i < paramCount; i++) {
                String name = Wire.readString(in, 128).toLowerCase(Locale.ROOT);
                int index = Wire.readVarInt(in);
                float def = in.readFloat();
                float min = in.readFloat();
                float max = in.readFloat();
                if (PARAM_NAME.matcher(name).matches() && index >= 0 && index < EffectDef.PARAM_COUNT
                        && Float.isFinite(def) && Float.isFinite(min) && Float.isFinite(max)
                        && usedIndexes.add(index) && usedNames.add(name)) {
                    params.add(new ParamDef(name, index, def, Math.min(min, max), Math.max(min, max)));
                }
            }
            if (!EffectLibrary.isCustomId(id) || map.containsKey(id)) {
                continue;
            }
            map.put(id, new EffectDef(id, true, sanitize(displayName), flashing, overHud,
                    Float.isFinite(intensity) ? EffectLibrary.clamp01(intensity) : 1f,
                    color & 0xFFFFFF, List.copyOf(params)));
        }
        Map<String, EffectDef> previous = caps.put(player.getUniqueId(), map);
        if (!map.equals(previous)) {
            sync(player, true); // слои, ждавшие эффект, теперь можно отправить
        }
    }

    private static String sanitize(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 48; i++) {
            char c = s.charAt(i);
            sb.append(c < 0x20 ? ' ' : c);
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------
    // События
    // ------------------------------------------------------------------

    @EventHandler
    public void onRegisterChannel(PlayerRegisterChannelEvent event) {
        if (!SYNC_CHANNEL.equals(event.getChannel())) {
            return;
        }
        Player player = event.getPlayer();
        lastSent.remove(player.getUniqueId());
        sync(player, true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        scheduleModCheck(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastSent.remove(uuid);
        statuses.remove(uuid);
        caps.remove(uuid);
        plugin.getBridge().forget(uuid);
    }

    private void scheduleModCheck(Player player) {
        if (!plugin.getConfig().getBoolean("settings.require-client-mod", false)) {
            return;
        }
        long seconds = Math.max(1L, plugin.getConfig().getLong("settings.mod-check-timeout-seconds", 10L));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()
                    || player.hasPermission(PostEffectControlPlugin.BYPASS_PERMISSION)
                    || hasMod(player)) {
                return;
            }
            player.kick(Component.text(Lang.get(Lang.Key.KICK_NO_MOD, player), NamedTextColor.RED));
        }, seconds * 20L);
    }
}
