package net.dvmn2.posteffectcontrolplugin;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

public final class Lang {

    public enum Key {
        PLAYER_NOT_FOUND,
        EFFECT_NOT_FOUND,
        EFFECT_UNAVAILABLE,
        BAD_DURATION,
        BAD_OPTION,
        LAYER_LIMIT,
        GIVE_OK,
        GIVE_NO_MOD,
        GIVE_PENDING,
        REMOVE_OK,
        REMOVE_NONE,
        CLEAR_OK,
        GET_HEADER,
        GET_ENTRY,
        GET_NONE,
        GET_STATUS,
        GET_NO_MOD,
        GET_PENDING,
        STATUS_UNKNOWN,
        STATUS_WORKING,
        STATUS_READY,
        STATUS_FAILED,
        LIST_HEADER,
        LIST_BUILTIN,
        LIST_CUSTOM,
        LIST_PRESET,
        INFO_HEADER,
        INFO_FLAGS,
        INFO_PRIORITY,
        INFO_PARAM,
        INFO_PRESET,
        RELOAD_OK,
        RELOAD_WARN,
        KICK_NO_MOD,
        ADMIN_CLIENT_FAILED,
        YES,
        NO
    }

    private static final Map<Key, String> RU = new EnumMap<>(Key.class);
    private static final Map<Key, String> EN = new EnumMap<>(Key.class);
    private static String configuredLanguage = "auto";

    private static void put(Key key, String ru, String en) {
        RU.put(key, ru);
        EN.put(key, en);
    }

    static {
        put(Key.PLAYER_NOT_FOUND, "Не удалось найти игрока.", "Player not found.");
        put(Key.EFFECT_NOT_FOUND, "Эффект или пресет '%s' не найден. Список: /posteffect list",
                "Effect or preset '%s' was not found. List: /posteffect list");
        put(Key.EFFECT_UNAVAILABLE, "У игрока %s нет эффекта '%s' в ресурспаках (и пак для него не настроен в resource-packs).",
                "Player %s does not have effect '%s' in their resource packs (and no pack is bound in resource-packs).");
        put(Key.BAD_DURATION, "Неверная длительность '%s'. Примеры: 30, 30s, 5m, 1h30m, inf",
                "Invalid duration '%s'. Examples: 30, 30s, 5m, 1h30m, inf");
        put(Key.BAD_OPTION, "Неверный параметр: %s", "Invalid option: %s");
        put(Key.LAYER_LIMIT, "У игрока %s слишком много слоёв (лимит %d). Сначала снимите что-нибудь.",
                "Player %s has too many layers (limit %d). Remove something first.");
        put(Key.GIVE_OK, "Эффект '%s' выдан: %d игрок(ов), длительность %s.",
                "Effect '%s' applied to %d player(s), duration %s.");
        put(Key.GIVE_NO_MOD, "У %s нет мода PostEffectControl — эффект применится, когда он зайдёт с модом.",
                "%s has no PostEffectControl mod — the effect will apply once they join with it.");
        put(Key.GIVE_PENDING, "У %s пока нет эффекта '%s': ждём, пока загрузится ресурспак — эффект появится сам.",
                "%s does not have effect '%s' yet: waiting for the resource pack to load — it will appear automatically.");
        put(Key.REMOVE_OK, "Эффект '%s' снят у %d игрок(ов).", "Effect '%s' removed from %d player(s).");
        put(Key.REMOVE_NONE, "Ни у кого из выбранных игроков нет эффекта '%s'.",
                "None of the selected players has effect '%s'.");
        put(Key.CLEAR_OK, "Все эффекты сняты у %d игрок(ов).", "All effects cleared for %d player(s).");
        put(Key.GET_HEADER, "Эффекты игрока %s:", "Effects of %s:");
        put(Key.GET_ENTRY, " • %s — осталось %s, интенсивность %.2f, слоёв %d",
                " • %s — remaining %s, intensity %.2f, layers %d");
        put(Key.GET_NONE, " (нет активных эффектов)", " (no active effects)");
        put(Key.GET_STATUS, "Клиент: %s", "Client: %s");
        put(Key.GET_NO_MOD, "Клиентский мод PostEffectControl не обнаружен.",
                "Client mod PostEffectControl was not detected.");
        put(Key.GET_PENDING, " ! %s ожидает ресурс у клиента (пак: %s)", " ! %s is waiting for a resource on the client (pack: %s)");
        put(Key.STATUS_UNKNOWN, "статус пока не получен", "no status yet");
        put(Key.STATUS_WORKING, "обновляет ресурсы (%s)", "reloading resources (%s)");
        put(Key.STATUS_READY, "всё применено (активных слоёв: %d, Iris: %s, рендер включён: %s)",
                "all applied (active layers: %d, Iris: %s, rendering enabled: %s)");
        put(Key.STATUS_FAILED, "ОШИБКА: %s (Iris: %s)", "FAILED: %s (Iris: %s)");
        put(Key.LIST_HEADER, "Доступные эффекты:", "Available effects:");
        put(Key.LIST_BUILTIN, " Встроенные: %s", " Built-in: %s");
        put(Key.LIST_CUSTOM, " Из ресурспаков (по данным клиентов онлайн): %s", " From resource packs (reported by online clients): %s");
        put(Key.LIST_PRESET, " Пресеты: %s", " Presets: %s");
        put(Key.INFO_HEADER, "Эффект %s (%s):", "Effect %s (%s):");
        put(Key.INFO_FLAGS, " мигающий: %s, поверх HUD: %s, интенсивность по умолчанию: %.2f",
                " flashing: %s, over HUD: %s, default intensity: %.2f");
        put(Key.INFO_PRIORITY, " приоритет слоя: %d (больше — выше; задаётся в config.yml: priorities)",
                " layer priority: %d (higher = drawn on top; set in config.yml: priorities)");
        put(Key.INFO_PARAM, " параметр %s (p%d): по умолчанию %.3f, диапазон %.3f..%.3f",
                " param %s (p%d): default %.3f, range %.3f..%.3f");
        put(Key.INFO_PRESET, " слой: %s, интенсивность %.2f", " layer: %s, intensity %.2f");
        put(Key.RELOAD_OK, "Перезагружено: встроенных %d, привязок к ресурспакам %d, пресетов %d.",
                "Reloaded: %d built-in, %d resource pack bindings, %d presets.");
        put(Key.RELOAD_WARN, "Предупреждение: %s", "Warning: %s");
        put(Key.KICK_NO_MOD, "Для игры на этом сервере нужен клиентский мод PostEffectControl.",
                "This server requires the PostEffectControl client mod.");
        put(Key.ADMIN_CLIENT_FAILED, "[PostEffectControl] У %s не применился эффект: %s",
                "[PostEffectControl] %s failed to apply an effect: %s");
        put(Key.YES, "да", "yes");
        put(Key.NO, "нет", "no");
    }

    private Lang() {
    }

    public static void setLanguage(String language) {
        configuredLanguage = (language == null || language.isBlank()) ? "auto" : language.toLowerCase(Locale.ROOT);
    }

    public static String get(Key key, CommandSender sender, Object... args) {
        Map<Key, String> table = switch (configuredLanguage) {
            case "en" -> EN;
            case "ru" -> RU;
            default -> sender instanceof Player p && "ru".equalsIgnoreCase(p.locale().getLanguage()) ? RU : EN;
        };
        String pattern = table.getOrDefault(key, RU.get(key));
        return args.length == 0 ? pattern : String.format(Locale.ROOT, pattern, args);
    }

    public static String yesNo(boolean value, CommandSender sender) {
        return get(value ? Key.YES : Key.NO, sender);
    }
}
