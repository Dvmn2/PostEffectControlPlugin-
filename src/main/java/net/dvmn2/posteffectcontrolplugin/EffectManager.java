package net.dvmn2.posteffectcontrolplugin;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Хранит назначенные эффекты каждого игрока (в том числе офлайн), считает истечение,
 * сохраняет в effects.yml. Все методы вызываются из основного потока.
 */
public final class EffectManager {

    public enum ApplyResult {OK, UNKNOWN, LIMIT}

    private final PostEffectControlPlugin plugin;
    private final Map<UUID, LinkedHashMap<String, Applied>> data = new HashMap<>();
    private boolean dirty;

    public EffectManager(PostEffectControlPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // Выдача / снятие
    // ------------------------------------------------------------------

    /**
     * @param durationMs длительность в мс, -1 — бессрочно
     */
    public ApplyResult apply(UUID uuid, String name, long durationMs, ApplyOptions options) {
        String key = name.toLowerCase(Locale.ROOT);
        EffectLibrary library = plugin.getLibrary();
        EffectLibrary.Preset preset = library.preset(key);
        EffectDef def = library.effect(key);
        boolean custom = def == null && preset == null && EffectLibrary.isCustomId(key);
        if (preset == null && def == null && !custom) {
            return ApplyResult.UNKNOWN;
        }

        List<Applied.Layer> layers = new ArrayList<>();
        if (preset != null) {
            for (EffectLibrary.PresetLayer pl : preset.layers()) {
                EffectDef layerDef = library.effect(pl.effectId());
                if (layerDef != null) {
                    float[] params = layerDef.defaultParams();
                    for (Map.Entry<String, Float> e : pl.params().entrySet()) {
                        applyParam(layerDef, params, e.getKey(), e.getValue());
                    }
                    layers.add(new Applied.Layer(layerDef.id(), pl.intensity() * layerDef.defaultIntensity(),
                            pl.color() != null ? pl.color() : layerDef.defaultColor(), params, Map.of()));
                } else if (EffectLibrary.isCustomId(pl.effectId())) {
                    layers.add(rawLayer(pl.effectId(), pl.intensity(), pl.color() != null ? pl.color() : Applied.COLOR_UNSET,
                            Map.of(), pl.params()));
                }
            }
        } else if (def != null) {
            float[] params = def.defaultParams();
            for (Map.Entry<Integer, Float> e : options.paramsByIndex.entrySet()) {
                applyParam(def, params, "p" + e.getKey(), e.getValue());
            }
            for (Map.Entry<String, Float> e : options.paramsByName.entrySet()) {
                applyParam(def, params, e.getKey(), e.getValue());
            }
            layers.add(new Applied.Layer(def.id(), def.defaultIntensity(),
                    options.color != null ? options.color : def.defaultColor(), params, Map.of()));
        } else {
            layers.add(rawLayer(key, 1f, options.color != null ? options.color : Applied.COLOR_UNSET,
                    options.paramsByIndex, options.paramsByName));
        }
        if (layers.isEmpty()) {
            return ApplyResult.UNKNOWN;
        }

        LinkedHashMap<String, Applied> map = data.computeIfAbsent(uuid, k -> new LinkedHashMap<>());
        int others = 0;
        for (Applied a : map.values()) {
            if (!a.source().equals(key)) {
                others += a.layers().size();
            }
        }
        int max = Math.max(1, plugin.getConfig().getInt("limits.max-layers-per-player", 12));
        if (others + layers.size() > max) {
            return ApplyResult.LIMIT;
        }

        int fadeIn = options.fadeInMs != null ? options.fadeInMs : plugin.getConfig().getInt("settings.default-fade-in-ms", 600);
        int fadeOut = options.fadeOutMs != null ? options.fadeOutMs : plugin.getConfig().getInt("settings.default-fade-out-ms", 800);
        float intensity = options.intensity != null ? EffectLibrary.clamp01(options.intensity) : 1f;
        long end = durationMs < 0 ? -1L : System.currentTimeMillis() + durationMs;

        map.remove(key); // повторная выдача уходит в конец (верхний слой)
        map.put(key, new Applied(key, List.copyOf(layers), intensity, end, Math.max(0, fadeIn), Math.max(0, fadeOut)));
        dirty = true;
        return ApplyResult.OK;
    }

    /**
     * Слой эффекта из ресурспака: значения по умолчанию неизвестны серверу, поэтому незаданное остаётся
     * «пустым» (NaN / COLOR_UNSET), а параметры по имени сохраняются как есть до отправки.
     * Ключи вида pN (по индексу) сразу кладутся в params.
     */
    private static Applied.Layer rawLayer(String id, float intensity, int color, Map<?, Float> byIndexOrName,
                                          Map<String, Float> byName) {
        float[] params = Applied.unsetParams();
        Map<String, Float> named = new LinkedHashMap<>();
        for (Map.Entry<?, Float> e : byIndexOrName.entrySet()) {
            if (e.getKey() instanceof Integer index) {
                if (index >= 0 && index < params.length) {
                    params[index] = e.getValue();
                }
            } else {
                putNamedOrIndexed(params, named, e.getKey().toString(), e.getValue());
            }
        }
        for (Map.Entry<String, Float> e : byName.entrySet()) {
            putNamedOrIndexed(params, named, e.getKey(), e.getValue());
        }
        return new Applied.Layer(id, intensity, color, params, Map.copyOf(named));
    }

    private static void putNamedOrIndexed(float[] params, Map<String, Float> named, String name, float value) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.length() == 2 && n.charAt(0) == 'p' && Character.isDigit(n.charAt(1)) && n.charAt(1) - '0' < params.length) {
            params[n.charAt(1) - '0'] = value;
        } else {
            named.put(n, value);
        }
    }

    private static void applyParam(EffectDef def, float[] params, String name, float value) {
        int index = -1;
        EffectDef.ParamDef pd = def.param(name);
        if (pd != null) {
            index = pd.index();
        } else if (name.length() == 2 && name.charAt(0) == 'p' && Character.isDigit(name.charAt(1))) {
            index = name.charAt(1) - '0';
            pd = def.paramByIndex(index);
        }
        if (index < 0 || index >= EffectDef.PARAM_COUNT) {
            return;
        }
        params[index] = pd != null ? Math.max(pd.min(), Math.min(pd.max(), value)) : value;
    }

    public boolean remove(UUID uuid, String name) {
        LinkedHashMap<String, Applied> map = data.get(uuid);
        if (map == null || map.remove(name.toLowerCase(Locale.ROOT)) == null) {
            return false;
        }
        dirty = true;
        return true;
    }

    public boolean clear(UUID uuid) {
        LinkedHashMap<String, Applied> map = data.remove(uuid);
        if (map == null || map.isEmpty()) {
            return false;
        }
        dirty = true;
        return true;
    }

    public List<Applied> get(UUID uuid) {
        LinkedHashMap<String, Applied> map = data.get(uuid);
        return map == null ? List.of() : List.copyOf(map.values());
    }

    /** Убирает истёкшие назначения. @return игроки, у которых что-то истекло. */
    public Set<UUID> expire(long now) {
        Set<UUID> changed = new HashSet<>();
        Iterator<Map.Entry<UUID, LinkedHashMap<String, Applied>>> it = data.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, LinkedHashMap<String, Applied>> e = it.next();
            boolean any = e.getValue().values().removeIf(a -> a.expired(now));
            if (any) {
                changed.add(e.getKey());
                dirty = true;
            }
            if (e.getValue().isEmpty()) {
                it.remove();
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------
    // Сохранение
    // ------------------------------------------------------------------

    private File file() {
        return new File(plugin.getDataFolder(), "effects.yml");
    }

    public void load() {
        data.clear();
        File f = file();
        if (!f.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (String uuidText : players.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidText);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            ConfigurationSection sources = players.getConfigurationSection(uuidText);
            if (sources == null) {
                continue;
            }
            LinkedHashMap<String, Applied> map = new LinkedHashMap<>();
            for (String entryKey : sources.getKeys(false)) {
                ConfigurationSection s = sources.getConfigurationSection(entryKey);
                if (s == null) {
                    continue;
                }
                // Идентификатор ns:path может содержать '.', а Bukkit делит пути по '.', поэтому
                // источник хранится в поле source, а не в ключе (старый формат: ключ = источник).
                String source = s.getString("source", entryKey).toLowerCase(Locale.ROOT);
                long end = s.getLong("end", -1L);
                if (end >= 0 && end <= now) {
                    continue;
                }
                List<Applied.Layer> layers = new ArrayList<>();
                for (Map<?, ?> raw : s.getMapList("layers")) {
                    Object id = raw.get("effect");
                    if (id == null) {
                        continue;
                    }
                    String effectId = id.toString().toLowerCase(Locale.ROOT);
                    boolean custom = EffectLibrary.isCustomId(effectId);
                    if (!custom && plugin.getLibrary().effect(effectId) == null) {
                        continue;
                    }
                    float[] params = Applied.unsetParams();
                    if (raw.get("params") instanceof List<?> list) {
                        for (int i = 0; i < Math.min(list.size(), params.length); i++) {
                            if (list.get(i) instanceof Number n) {
                                params[i] = n.floatValue();
                            }
                        }
                    }
                    Map<String, Float> named = new LinkedHashMap<>();
                    if (raw.get("named") instanceof Map<?, ?> nm) {
                        for (Map.Entry<?, ?> ne : nm.entrySet()) {
                            if (ne.getValue() instanceof Number n) {
                                named.put(ne.getKey().toString().toLowerCase(Locale.ROOT), n.floatValue());
                            }
                        }
                    }
                    int color;
                    if (raw.get("color") instanceof Number n) {
                        color = n.intValue() < 0 ? Applied.COLOR_UNSET : n.intValue() & 0xFFFFFF;
                    } else {
                        color = custom ? Applied.COLOR_UNSET : 0xFFFFFF;
                    }
                    layers.add(new Applied.Layer(effectId,
                            raw.get("intensity") instanceof Number n ? n.floatValue() : 1f,
                            color, params, Map.copyOf(named)));
                }
                if (!layers.isEmpty()) {
                    map.put(source, new Applied(source, List.copyOf(layers), (float) s.getDouble("intensity", 1.0),
                            end, s.getInt("fade-in", 0), s.getInt("fade-out", 0)));
                }
            }
            if (!map.isEmpty()) {
                data.put(uuid, map);
            }
        }
    }

    public void saveIfDirty() {
        if (dirty) {
            save();
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, LinkedHashMap<String, Applied>> e : data.entrySet()) {
            int n = 0;
            for (Applied a : e.getValue().values()) {
                String base = "players." + e.getKey() + ".e" + (n++);
                yaml.set(base + ".source", a.source());
                yaml.set(base + ".intensity", a.intensity());
                yaml.set(base + ".end", a.endMillis());
                yaml.set(base + ".fade-in", a.fadeInMs());
                yaml.set(base + ".fade-out", a.fadeOutMs());
                List<Map<String, Object>> layers = new ArrayList<>();
                for (Applied.Layer l : a.layers()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("effect", l.effectId());
                    m.put("intensity", l.intensity());
                    m.put("color", l.color());
                    List<Double> ps = new ArrayList<>();
                    for (float p : l.params()) {
                        ps.add(Float.isNaN(p) ? null : (double) p); // null = «не задан»
                    }
                    m.put("params", ps);
                    if (!l.named().isEmpty()) {
                        Map<String, Double> named = new LinkedHashMap<>();
                        l.named().forEach((k, v) -> named.put(k, (double) v));
                        m.put("named", named);
                    }
                    layers.add(m);
                }
                yaml.set(base + ".layers", layers);
            }
        }
        try {
            yaml.save(file());
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().warning("Не удалось сохранить effects.yml: " + ex.getMessage());
        }
    }
}
