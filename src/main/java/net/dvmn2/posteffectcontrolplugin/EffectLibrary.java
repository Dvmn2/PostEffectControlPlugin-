package net.dvmn2.posteffectcontrolplugin;

import net.dvmn2.posteffectcontrolplugin.EffectDef.ParamDef;
import org.bukkit.configuration.ConfigurationSection;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Каталог эффектов: встроенные (шейдеры внутри мода) и пресеты из config.yml.
 * <p>
 * Эффекты из ресурспаков здесь не хранятся: сервер ничего не знает о ресурспаках клиента.
 * Их идентификатор {@code ns:path} принимается по синтаксису ({@link #isCustomId}), а описание
 * (параметры, значения по умолчанию) берётся из списка, который присылает клиент ({@link NetworkManager#caps}).
 */
public final class EffectLibrary {

    /** Идентификатор пресета (и короткое имя встроенного эффекта). */
    public static final Pattern ID = Pattern.compile("[a-z0-9_]{1,24}");
    /** Идентификатор эффекта из ресурспака: {@code namespace:path}, как у Identifier. */
    private static final Pattern CUSTOM_ID = Pattern.compile("[a-z0-9_.-]{1,40}:[a-z0-9_./-]{1,70}");
    /** Должен совпадать с лимитом клиента: идентификатор входит в ключи слоёв и сетевые строки. */
    public static final int MAX_CUSTOM_ID = 80;

    /** Слой пресета; params — по имени или pN. */
    public record PresetLayer(String effectId, float intensity, Integer color, Map<String, Float> params) {
    }

    public record Preset(String id, String displayName, List<PresetLayer> layers) {
    }

    private final PostEffectControlPlugin plugin;
    private final Map<String, EffectDef> effects = new LinkedHashMap<>();
    private final Map<String, Preset> presets = new LinkedHashMap<>();
    /** id эффекта -> приоритет слоя: чем больше, тем позже (выше) рисуется. Не указанные = 0. */
    private final Map<String, Integer> priorities = new HashMap<>();

    public static final int MIN_PRIORITY = -1000;
    public static final int MAX_PRIORITY = 1000;

    public EffectLibrary(PostEffectControlPlugin plugin) {
        this.plugin = plugin;
    }

    /** Эффект из ресурспака клиента (namespace minecraft зарезервирован за короткими именами встроенных). */
    public static boolean isCustomId(String s) {
        return s != null && s.length() <= MAX_CUSTOM_ID && CUSTOM_ID.matcher(s).matches() && !s.startsWith("minecraft:");
    }

    // ------------------------------------------------------------------
    // Загрузка
    // ------------------------------------------------------------------

    /** @return предупреждения (для вывода админу). */
    public synchronized List<String> load() {
        List<String> warnings = new ArrayList<>();
        effects.clear();
        presets.clear();
        priorities.clear();
        registerBuiltins();
        loadPresets(warnings);
        loadPriorities(warnings);
        return warnings;
    }

    /**
     * config.yml: {@code priorities:} id эффекта -> число. Bukkit делит ключи по '.', поэтому идентификаторы
     * с точкой в namespace приходят вложенными секциями — обходим все ключи и собираем путь обратно.
     */
    private void loadPriorities(List<String> warnings) {
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("priorities");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(true)) {
            Object value = root.get(key);
            if (value instanceof ConfigurationSection) {
                continue;
            }
            String id = key.toLowerCase(Locale.ROOT);
            if (!(value instanceof Number n)) {
                warnings.add("priorities." + key + ": ожидается число");
                continue;
            }
            if (!ID.matcher(id).matches() && !isCustomId(id)) {
                warnings.add("priorities." + key + ": неверный идентификатор эффекта");
                continue;
            }
            if (n.doubleValue() != Math.rint(n.doubleValue())
                    || n.intValue() < MIN_PRIORITY || n.intValue() > MAX_PRIORITY) {
                warnings.add("priorities." + key + ": нужно целое число " + MIN_PRIORITY + ".." + MAX_PRIORITY);
                continue;
            }
            priorities.put(id, n.intValue());
        }
    }

    private void registerBuiltins() {
        add("freeze", "Freeze", false, 0xA6D9FF,
                p("coverage", 0, 0.7f, 0f, 1f), p("blur", 1, 0.4f, 0f, 1f), p("tint", 2, 0.5f, 0f, 1f));
        add("blur", "Blur", false, 0xFFFFFF, p("radius", 0, 6f, 0f, 24f));
        add("grayscale", "Grayscale", false, 0xFFFFFF);
        add("sepia", "Sepia", false, 0xFFFFFF);
        add("invert", "Invert", false, 0xFFFFFF);
        add("pixelate", "Pixelate", false, 0xFFFFFF, p("size", 0, 10f, 1f, 64f));
        add("vignette", "Vignette", false, 0x000000,
                p("radius", 0, 0.35f, 0f, 1f), p("softness", 1, 0.6f, 0.05f, 1f));
        add("wobble", "Wobble", false, 0xFFFFFF,
                p("amplitude", 0, 0.012f, 0f, 0.1f), p("frequency", 1, 14f, 1f, 60f), p("speed", 2, 2f, 0f, 10f));
        add("chromatic", "Chromatic aberration", false, 0xFFFFFF, p("offset", 0, 0.012f, 0f, 0.1f));
        add("glitch", "Glitch", true, 0xFFFFFF, p("amount", 0, 1f, 0f, 2f));
        add("crt", "CRT", false, 0xFFFFFF, p("scanlines", 0, 0.8f, 0f, 1f));
        add("heartbeat", "Heartbeat", false, 0xCC0000, p("bpm", 0, 80f, 30f, 200f));
    }

    private static ParamDef p(String name, int index, float def, float min, float max) {
        return new ParamDef(name, index, def, min, max);
    }

    private void add(String id, String name, boolean flashing, int color, ParamDef... params) {
        effects.put(id, new EffectDef(id, false, name, flashing, false, 1f, color, List.of(params)));
    }

    private void loadPresets(List<String> warnings) {
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("presets");
        if (root == null) {
            return;
        }
        for (String id : root.getKeys(false)) {
            String key = id.toLowerCase(Locale.ROOT);
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            if (effects.containsKey(key)) {
                warnings.add("presets." + id + ": имя совпадает с эффектом");
                continue;
            }
            List<PresetLayer> layers = new ArrayList<>();
            for (Map<?, ?> raw : s.getMapList("layers")) {
                Object e = raw.get("effect");
                String effectId = e == null ? "" : e.toString().toLowerCase(Locale.ROOT);
                if (!effects.containsKey(effectId) && !isCustomId(effectId)) {
                    warnings.add("presets." + id + ": неизвестный эффект '" + e + "'");
                    continue;
                }
                float intensity = raw.get("intensity") instanceof Number n ? clamp01(n.floatValue()) : 1f;
                Integer color = null;
                if (raw.get("color") != null) {
                    try {
                        color = parseColor(raw.get("color").toString());
                    } catch (IllegalArgumentException ex) {
                        warnings.add("presets." + id + ": неверный цвет '" + raw.get("color") + "'");
                    }
                }
                Map<String, Float> params = new HashMap<>();
                if (raw.get("params") instanceof Map<?, ?> pm) {
                    for (Map.Entry<?, ?> pe : pm.entrySet()) {
                        if (pe.getValue() instanceof Number n) {
                            params.put(pe.getKey().toString().toLowerCase(Locale.ROOT), n.floatValue());
                        }
                    }
                }
                layers.add(new PresetLayer(effectId, intensity, color, params));
            }
            if (layers.isEmpty()) {
                warnings.add("presets." + id + ": нет корректных слоёв");
                continue;
            }
            presets.put(key, new Preset(key, s.getString("display-name", id), List.copyOf(layers)));
        }
    }

    // ------------------------------------------------------------------
    // Доступ
    // ------------------------------------------------------------------

    /** Только встроенные эффекты; эффекты из ресурспаков — см. {@link NetworkManager#effectFor}. */
    public synchronized EffectDef effect(String id) {
        return id == null ? null : effects.get(id.toLowerCase(Locale.ROOT));
    }

    /** Приоритет слоя эффекта (0, если не задан в config.yml). Больше = рисуется позже, то есть выше. */
    public synchronized int priority(String effectId) {
        return effectId == null ? 0 : priorities.getOrDefault(effectId.toLowerCase(Locale.ROOT), 0);
    }

    public synchronized Preset preset(String id) {
        return id == null ? null : presets.get(id.toLowerCase(Locale.ROOT));
    }

    public synchronized List<String> builtinIds() {
        return List.copyOf(effects.keySet());
    }

    public synchronized List<String> presetIds() {
        return List.copyOf(presets.keySet());
    }

    /** Короткие имена, которые можно выдать командой: встроенные эффекты и пресеты. */
    public synchronized List<String> giveableNames() {
        List<String> list = new ArrayList<>(effects.keySet());
        list.addAll(presets.keySet());
        return list;
    }

    public synchronized int builtinCount() {
        return effects.size();
    }

    // ------------------------------------------------------------------
    // Утилиты
    // ------------------------------------------------------------------

    /** #rrggbb, rrggbb или 0xrrggbb. */
    public static int parseColor(String text) {
        String s = text.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        } else if (s.startsWith("0x") || s.startsWith("0X")) {
            s = s.substring(2);
        }
        if (s.length() != 6) {
            throw new IllegalArgumentException(text);
        }
        return Integer.parseInt(s, 16) & 0xFFFFFF;
    }

    public static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
