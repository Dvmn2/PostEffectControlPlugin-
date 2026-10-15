package net.dvmn2.posteffectcontrolplugin;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Необязательная интеграция с ResourceControlPlugin (softdepend).
 * <p>
 * Если для эффекта из ресурспака в config.yml задан пак ({@code resource-packs}), а у игрока этого эффекта нет,
 * плагин временно выдаёт игроку нужный пак через API ResourceControlPlugin и снимает его, когда эффект
 * больше не нужен. Если у игрока эффект уже есть (пак установлен вручную или выдан через ResourceControl),
 * ничего не делается.
 * <p>
 * API ResourceControlPlugin вызывается через рефлексию: плагины остаются независимыми, ни один не требует
 * другой при компиляции, и PostEffectControlPlugin работает без ResourceControlPlugin.
 */
public final class ResourceBridge {

    private static final String PLUGIN_NAME = "ResourceControlPlugin";
    private static final String API_CLASS = "net.dvmn2.resourcecontrolplugin.api.ResourceControlApi";
    /** На сколько секунд выдаётся пак за раз; пока эффект нужен, выдача продлевается заранее. */
    private static final int GRANT_SECONDS = 3600;
    private static final long REGRANT_BEFORE_MS = 600_000L;

    private final PostEffectControlPlugin plugin;
    /** id эффекта или namespace -> имя пака в ResourceControlPlugin. */
    private final Map<String, String> bindings = new LinkedHashMap<>();
    /** Что выдали мы: игрок -> (пак -> когда истекает выдача). */
    private final Map<UUID, Map<String, Long>> granted = new HashMap<>();
    private final Set<String> warnedPacks = new HashSet<>();

    private Object api;
    private Method hasPack;
    private Method packExists;
    private Method grantTemporary;
    private Method revokeTemporary;
    private boolean warnedBroken;

    public ResourceBridge(PostEffectControlPlugin plugin) {
        this.plugin = plugin;
    }

    /** Перечитывает привязки из config.yml (resource-packs). @return предупреждения. */
    public List<String> reload() {
        List<String> warnings = new ArrayList<>();
        bindings.clear();
        warnedPacks.clear();
        for (Map<?, ?> raw : plugin.getConfig().getMapList("resource-packs")) {
            Object id = raw.get("id");
            Object pack = raw.get("pack");
            if (id == null || pack == null) {
                warnings.add("resource-packs: у записи должны быть поля id и pack");
                continue;
            }
            String key = id.toString().toLowerCase(Locale.ROOT);
            if (!key.contains(":") && !key.matches("[a-z0-9_.-]{1,40}")) {
                warnings.add("resource-packs: неверный id '" + id + "'");
                continue;
            }
            if (key.contains(":") && !EffectLibrary.isCustomId(key)) {
                warnings.add("resource-packs: неверный id '" + id + "'");
                continue;
            }
            bindings.put(key, pack.toString());
        }
        return warnings;
    }

    public int bindingCount() {
        return bindings.size();
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("settings.resourcecontrol-integration", true);
    }

    /** Имя пака для эффекта: сначала точное совпадение id, затем namespace. null — привязки нет. */
    public String packFor(String effectId) {
        if (!enabled()) {
            return null;
        }
        String exact = bindings.get(effectId);
        if (exact != null) {
            return exact;
        }
        int colon = effectId.indexOf(':');
        return colon > 0 ? bindings.get(effectId.substring(0, colon)) : null;
    }

    // ------------------------------------------------------------------
    // Подключение к API ResourceControlPlugin
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private boolean connect() {
        Plugin rc = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        if (rc == null || !rc.isEnabled()) {
            api = null;
            return false;
        }
        if (api != null) {
            return true;
        }
        try {
            Class<?> cls = Class.forName(API_CLASS, true, rc.getClass().getClassLoader());
            RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration((Class<Object>) cls);
            if (registration == null) {
                return false;
            }
            Object provider = registration.getProvider();
            hasPack = cls.getMethod("hasPack", Player.class, String.class);
            packExists = cls.getMethod("packExists", String.class);
            grantTemporary = cls.getMethod("grantTemporary", Player.class, String.class, int.class);
            revokeTemporary = cls.getMethod("revokeTemporary", Player.class, String.class);
            api = provider;
            return true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            if (!warnedBroken) {
                warnedBroken = true;
                plugin.getLogger().warning("Не удалось подключиться к API ResourceControlPlugin (нужна версия с ResourceControlApi): " + ex);
            }
            api = null;
            return false;
        }
    }

    private boolean call(Method method, Object... args) {
        try {
            return Boolean.TRUE.equals(method.invoke(api, args));
        } catch (ReflectiveOperationException | RuntimeException ex) {
            plugin.getLogger().warning("Ошибка вызова API ResourceControlPlugin: " + ex);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Выдача и снятие паков
    // ------------------------------------------------------------------

    /**
     * Приводит набор временно выданных игроку паков в соответствие с его эффектами. Дешёвый, можно звать часто.
     */
    public void ensure(Player player) {
        UUID uuid = player.getUniqueId();
        Map<String, Long> mine = granted.get(uuid);
        if ((!enabled() || bindings.isEmpty()) && mine == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Map<String, EffectDef> clientCaps = plugin.getNetwork().caps(uuid);
        // Пак нужен, если эффект есть в назначениях и либо его ещё нет у клиента, либо пак выдали мы
        // (иначе после загрузки пака мы тут же сняли бы его обратно).
        Set<String> needed = new LinkedHashSet<>();
        if (enabled()) {
            for (Applied applied : plugin.getManager().get(uuid)) {
                if (applied.expired(now)) {
                    continue;
                }
                for (Applied.Layer layer : applied.layers()) {
                    if (!EffectLibrary.isCustomId(layer.effectId())) {
                        continue;
                    }
                    String pack = packFor(layer.effectId());
                    if (pack == null) {
                        continue;
                    }
                    boolean clientHas = clientCaps != null && clientCaps.containsKey(layer.effectId());
                    if (!clientHas || (mine != null && mine.containsKey(pack))) {
                        needed.add(pack);
                    }
                }
            }
        }
        if ((!needed.isEmpty() || mine != null) && !connect()) {
            return;
        }
        if (!needed.isEmpty()) {
            if (mine == null) {
                mine = granted.computeIfAbsent(uuid, k -> new HashMap<>());
            }
            for (String pack : needed) {
                if (!call(packExists, pack)) {
                    if (warnedPacks.add(pack)) {
                        plugin.getLogger().warning("В ResourceControlPlugin нет пака '" + pack + "' (config.yml: resource-packs)");
                    }
                    continue;
                }
                Long expiry = mine.get(pack);
                boolean has = call(hasPack, player, pack);
                if (!has || (expiry != null && expiry - now < REGRANT_BEFORE_MS)) {
                    if (call(grantTemporary, player, pack, GRANT_SECONDS)) {
                        mine.put(pack, now + GRANT_SECONDS * 1000L);
                    }
                }
            }
        }
        if (mine != null) {
            Iterator<String> it = mine.keySet().iterator();
            while (it.hasNext()) {
                String pack = it.next();
                if (!needed.contains(pack)) {
                    call(revokeTemporary, player, pack);
                    it.remove();
                }
            }
            if (mine.isEmpty()) {
                granted.remove(uuid);
            }
        }
    }

    /** Игрок вышел: ResourceControlPlugin сам забывает временные выдачи. */
    public void forget(UUID uuid) {
        granted.remove(uuid);
    }

    /** Периодический вызов: продлевает выдачи и снимает паки у истёкших эффектов. */
    public void tick() {
        if (!enabled()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            if (NetworkManager.hasMod(player) && (granted.containsKey(uuid) || !plugin.getManager().get(uuid).isEmpty())) {
                ensure(player);
            }
        }
    }
}
