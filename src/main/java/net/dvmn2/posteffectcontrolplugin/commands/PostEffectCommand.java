package net.dvmn2.posteffectcontrolplugin.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import net.dvmn2.posteffectcontrolplugin.*;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;

/**
 * /posteffect give &lt;игроки&gt; &lt;эффект|пресет&gt; [длительность] [опции]
 * /posteffect remove &lt;игроки&gt; &lt;эффект|пресет&gt;
 * /posteffect clear &lt;игроки&gt;
 * /posteffect get &lt;игрок&gt;
 * /posteffect list | info &lt;эффект&gt; | reload
 * <p>
 * Эффект: короткое имя встроенного эффекта или пресета (freeze) либо эффект из ресурспака клиента
 * в виде namespace:path (mypack:fx/glitch). Опции: intensity=0.8 color=#66ccff fadein=1s fadeout=2s
 * p0..p7=число или имя параметра=число. Требует право posteffect.admin.
 */
public final class PostEffectCommand {

    private static final Pattern PARAM_NAME = Pattern.compile("[a-z0-9_]{1,24}");

    private final PostEffectControlPlugin plugin;

    public PostEffectCommand(PostEffectControlPlugin plugin) {
        this.plugin = plugin;
    }

    public LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("posteffect")
                .requires(src -> src.getSender().hasPermission(PostEffectControlPlugin.ADMIN_PERMISSION))
                .then(Commands.literal("give")
                        .then(playersArg()
                                .then(effectArg()
                                        .executes(ctx -> give(ctx, null, ""))
                                        .then(Commands.argument("duration", StringArgumentType.word())
                                                .suggests(this::suggestDurations)
                                                .executes(ctx -> give(ctx, StringArgumentType.getString(ctx, "duration"), ""))
                                                .then(Commands.argument("options", StringArgumentType.greedyString())
                                                        .suggests(this::suggestOptions)
                                                        .executes(ctx -> give(ctx,
                                                                StringArgumentType.getString(ctx, "duration"),
                                                                StringArgumentType.getString(ctx, "options"))))))))
                .then(Commands.literal("remove")
                        .then(playersArg().then(effectArg().executes(this::remove))))
                .then(Commands.literal("clear").then(playersArg().executes(this::clear)))
                .then(Commands.literal("get").then(Commands.argument("player", ArgumentTypes.player()).executes(this::get)))
                .then(Commands.literal("list").executes(this::list))
                .then(Commands.literal("info").then(effectArg().executes(this::info)))
                .then(Commands.literal("reload").executes(this::reload));
    }

    // ------------------------------------------------------------------
    // Аргументы
    // ------------------------------------------------------------------

    private RequiredArgumentBuilder<CommandSourceStack, ?> playersArg() {
        return Commands.argument("players", ArgumentTypes.players());
    }

    /**
     * Эффект задаётся ресурсным идентификатором: без namespace (freeze) — встроенный эффект или пресет
     * (Paper подставляет minecraft:), с namespace (mypack:fx/glitch) — эффект из ресурспака клиента.
     */
    private RequiredArgumentBuilder<CommandSourceStack, Key> effectArg() {
        return Commands.argument("effect", ArgumentTypes.key()).suggests((ctx, builder) -> {
            String typed = builder.getRemainingLowerCase();
            Set<String> names = new LinkedHashSet<>(plugin.getLibrary().giveableNames());
            names.addAll(plugin.getNetwork().knownCustomIds());
            for (String name : names) {
                if (name.startsWith(typed)) {
                    builder.suggest(name);
                }
            }
            return builder.buildFuture();
        });
    }

    /** Имя из аргумента: minecraft:freeze -> freeze, mypack:fx/glitch остаётся как есть. */
    private static String effectName(CommandContext<CommandSourceStack> ctx) {
        Key key = ctx.getArgument("effect", Key.class);
        return key.namespace().equals("minecraft") ? key.value() : key.asString();
    }

    private CompletableFuture<Suggestions> suggestDurations(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        for (String s : List.of("10s", "30s", "1m", "5m", "inf")) {
            if (s.startsWith(builder.getRemainingLowerCase())) {
                builder.suggest(s);
            }
        }
        return builder.buildFuture();
    }

    /** Подсказки для последнего слова greedy-аргумента с опциями. */
    private CompletableFuture<Suggestions> suggestOptions(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining();
        int wordStart = remaining.lastIndexOf(' ') + 1;
        String word = remaining.substring(wordStart).toLowerCase(Locale.ROOT);
        if (word.contains("=")) {
            return builder.buildFuture();
        }
        SuggestionsBuilder sub = builder.createOffset(builder.getStart() + wordStart);
        List<String> keys = new ArrayList<>(List.of("intensity=", "color=", "fadein=", "fadeout="));
        EffectDef def = null;
        try {
            def = lookup(effectName(ctx));
        } catch (IllegalArgumentException ignored) {
            // аргумент ещё не разобран — подсказываем только общие ключи
        }
        if (def != null) {
            for (EffectDef.ParamDef p : def.params()) {
                keys.add(p.name() + "=");
            }
        }
        for (String k : keys) {
            if (k.startsWith(word)) {
                sub.suggest(k);
            }
        }
        return sub.buildFuture();
    }

    private List<Player> players(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        List<Player> list = ctx.getArgument("players", PlayerSelectorArgumentResolver.class).resolve(ctx.getSource());
        if (list.isEmpty()) {
            error(ctx.getSource().getSender(), Lang.Key.PLAYER_NOT_FOUND);
        }
        return list;
    }

    // ------------------------------------------------------------------
    // Сообщения
    // ------------------------------------------------------------------

    private static void ok(CommandSender s, Lang.Key key, Object... args) {
        s.sendMessage(Component.text(Lang.get(key, s, args), NamedTextColor.GREEN));
    }

    private static void warn(CommandSender s, Lang.Key key, Object... args) {
        s.sendMessage(Component.text(Lang.get(key, s, args), NamedTextColor.YELLOW));
    }

    private static void error(CommandSender s, Lang.Key key, Object... args) {
        s.sendMessage(Component.text(Lang.get(key, s, args), NamedTextColor.RED));
    }

    private static void info(CommandSender s, String text) {
        s.sendMessage(Component.text(text, NamedTextColor.GRAY));
    }

    // ------------------------------------------------------------------
    // Команды
    // ------------------------------------------------------------------

    /** Описание эффекта без привязки к игроку: встроенный или (по данным любого клиента онлайн) из ресурспака. */
    private EffectDef lookup(String name) {
        EffectDef def = plugin.getLibrary().effect(name);
        return def != null ? def : plugin.getNetwork().anyCustom(name);
    }

    private boolean knownName(String name) {
        return plugin.getLibrary().effect(name) != null || plugin.getLibrary().preset(name) != null
                || EffectLibrary.isCustomId(name);
    }

    private int give(CommandContext<CommandSourceStack> ctx, String durationText, String optionsText) throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        String name = effectName(ctx).toLowerCase(Locale.ROOT);
        boolean packEffects = plugin.getConfig().getBoolean("settings.allow-pack-effects", true);
        if (!knownName(name) || (EffectLibrary.isCustomId(name) && !packEffects)) {
            error(sender, Lang.Key.EFFECT_NOT_FOUND, name);
            return 0;
        }

        long durationMs;
        if (durationText == null) {
            long seconds = plugin.getConfig().getLong("settings.default-duration-seconds", 30L);
            durationMs = seconds <= 0 ? -1L : seconds * 1000L;
        } else {
            try {
                durationMs = Durations.parse(durationText);
            } catch (IllegalArgumentException ex) {
                error(sender, Lang.Key.BAD_DURATION, durationText);
                return 0;
            }
        }

        ApplyOptions options;
        try {
            options = parseOptions(optionsText);
        } catch (IllegalArgumentException ex) {
            error(sender, Lang.Key.BAD_OPTION, ex.getMessage());
            return 0;
        }

        boolean custom = EffectLibrary.isCustomId(name);
        List<Player> targets = players(ctx);
        int applied = 0;
        for (Player player : targets) {
            if (custom) {
                // Эффект из ресурспака: проверяем по списку, который прислал клиент.
                EffectDef def = plugin.getNetwork().effectFor(player.getUniqueId(), name);
                boolean hasMod = NetworkManager.hasMod(player);
                boolean capsKnown = plugin.getNetwork().caps(player.getUniqueId()) != null;
                boolean packBound = plugin.getBridge().packFor(name) != null;
                if (hasMod && capsKnown && def == null && !packBound) {
                    error(sender, Lang.Key.EFFECT_UNAVAILABLE, player.getName(), name);
                    continue;
                }
                String badParam = unknownParam(def, options);
                if (badParam != null) {
                    error(sender, Lang.Key.BAD_OPTION, badParam + " (" + player.getName() + ")");
                    continue;
                }
            } else if (plugin.getLibrary().effect(name) != null) {
                String badParam = unknownParam(plugin.getLibrary().effect(name), options);
                if (badParam != null) {
                    error(sender, Lang.Key.BAD_OPTION, badParam);
                    return 0;
                }
            }
            EffectManager.ApplyResult result = plugin.getManager().apply(player.getUniqueId(), name, durationMs, options);
            switch (result) {
                case OK -> {
                    applied++;
                    plugin.getNetwork().sync(player, false);
                    if (!NetworkManager.hasMod(player)) {
                        warn(sender, Lang.Key.GIVE_NO_MOD, player.getName());
                    } else if (custom && plugin.getNetwork().effectFor(player.getUniqueId(), name) == null) {
                        warn(sender, Lang.Key.GIVE_PENDING, player.getName(), name);
                    }
                }
                case LIMIT -> error(sender, Lang.Key.LAYER_LIMIT, player.getName(),
                        plugin.getConfig().getInt("limits.max-layers-per-player", 12));
                case UNKNOWN -> {
                    error(sender, Lang.Key.EFFECT_NOT_FOUND, name);
                    return 0;
                }
            }
        }
        if (applied > 0) {
            ok(sender, Lang.Key.GIVE_OK, name, applied, Durations.format(durationMs));
        }
        return applied > 0 ? Command.SINGLE_SUCCESS : 0;
    }

    /** Имя параметра из опций, которого нет в описании эффекта (если описание известно). */
    private static String unknownParam(EffectDef def, ApplyOptions options) {
        if (def == null) {
            return null;
        }
        for (String key : options.paramsByName.keySet()) {
            if (def.param(key) == null) {
                return key;
            }
        }
        return null;
    }

    /** intensity=0.8 color=#66ccff fadein=1s fadeout=2s fade=1s p0=1 radius=8 */
    private static ApplyOptions parseOptions(String text) {
        ApplyOptions o = ApplyOptions.defaults();
        if (text == null || text.isBlank()) {
            return o;
        }
        for (String token : text.trim().split("\\s+")) {
            int eq = token.indexOf('=');
            if (eq <= 0 || eq == token.length() - 1) {
                throw new IllegalArgumentException(token);
            }
            String key = token.substring(0, eq).toLowerCase(Locale.ROOT);
            String value = token.substring(eq + 1);
            try {
                switch (key) {
                    case "intensity", "i" -> o.intensity = Float.parseFloat(value);
                    case "color", "c" -> o.color = EffectLibrary.parseColor(value);
                    case "fadein", "fi" -> o.fadeInMs = (int) Math.max(0, Durations.parse(value));
                    case "fadeout", "fo" -> o.fadeOutMs = (int) Math.max(0, Durations.parse(value));
                    case "fade" -> {
                        int ms = (int) Math.max(0, Durations.parse(value));
                        o.fadeInMs = ms;
                        o.fadeOutMs = ms;
                    }
                    default -> {
                        float number = Float.parseFloat(value);
                        if (key.length() == 2 && key.charAt(0) == 'p' && Character.isDigit(key.charAt(1))) {
                            int index = key.charAt(1) - '0';
                            if (index >= EffectDef.PARAM_COUNT) {
                                throw new IllegalArgumentException(token);
                            }
                            o.paramsByIndex.put(index, number);
                        } else if (PARAM_NAME.matcher(key).matches()) {
                            o.paramsByName.put(key, number); // существование имени проверяется для каждого игрока отдельно
                        } else {
                            throw new IllegalArgumentException(token);
                        }
                    }
                }
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(token);
            }
        }
        return o;
    }

    private int remove(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        String name = effectName(ctx).toLowerCase(Locale.ROOT);
        int removed = 0;
        for (Player player : players(ctx)) {
            if (plugin.getManager().remove(player.getUniqueId(), name)) {
                removed++;
                plugin.getNetwork().sync(player, false);
            }
        }
        if (removed > 0) {
            ok(sender, Lang.Key.REMOVE_OK, name, removed);
        } else {
            warn(sender, Lang.Key.REMOVE_NONE, name);
        }
        return removed;
    }

    private int clear(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        int count = 0;
        for (Player player : players(ctx)) {
            plugin.getManager().clear(player.getUniqueId());
            plugin.getNetwork().sync(player, false);
            count++;
        }
        ok(sender, Lang.Key.CLEAR_OK, count);
        return count;
    }

    private int get(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        List<Player> list = ctx.getArgument("player", PlayerSelectorArgumentResolver.class).resolve(ctx.getSource());
        if (list.isEmpty()) {
            error(sender, Lang.Key.PLAYER_NOT_FOUND);
            return 0;
        }
        Player player = list.get(0);
        long now = System.currentTimeMillis();
        sender.sendMessage(Component.text(Lang.get(Lang.Key.GET_HEADER, sender, player.getName()), NamedTextColor.AQUA));
        List<Applied> applied = plugin.getManager().get(player.getUniqueId());
        if (applied.isEmpty()) {
            info(sender, Lang.get(Lang.Key.GET_NONE, sender));
        }
        for (Applied a : applied) {
            long remaining = a.infinite() ? -1L : Math.max(0L, a.endMillis() - now);
            info(sender, Lang.get(Lang.Key.GET_ENTRY, sender, a.source(), Durations.format(remaining),
                    a.intensity(), a.layers().size()));
        }
        if (!NetworkManager.hasMod(player)) {
            warn(sender, Lang.Key.GET_NO_MOD);
            return Command.SINGLE_SUCCESS;
        }
        for (Applied a : applied) {
            for (Applied.Layer layer : a.layers()) {
                if (EffectLibrary.isCustomId(layer.effectId())
                        && plugin.getNetwork().effectFor(player.getUniqueId(), layer.effectId()) == null) {
                    String pack = plugin.getBridge().packFor(layer.effectId());
                    warn(sender, Lang.Key.GET_PENDING, layer.effectId(), pack == null ? "-" : pack);
                }
            }
        }
        NetworkManager.ClientStatus st = plugin.getNetwork().status(player.getUniqueId());
        String text;
        if (st == null) {
            text = Lang.get(Lang.Key.STATUS_UNKNOWN, sender);
        } else {
            String iris = Lang.yesNo(st.iris(), sender);
            text = switch (st.state()) {
                case NetworkManager.STATE_WORKING -> Lang.get(Lang.Key.STATUS_WORKING, sender, st.message());
                case NetworkManager.STATE_FAILED -> Lang.get(Lang.Key.STATUS_FAILED, sender, st.message(), iris);
                default -> Lang.get(Lang.Key.STATUS_READY, sender, st.activeLayers(), iris,
                        Lang.yesNo(st.rendering(), sender));
            };
        }
        info(sender, Lang.get(Lang.Key.GET_STATUS, sender, text));
        return Command.SINGLE_SUCCESS;
    }

    private int list(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        EffectLibrary lib = plugin.getLibrary();
        sender.sendMessage(Component.text(Lang.get(Lang.Key.LIST_HEADER, sender), NamedTextColor.AQUA));
        info(sender, Lang.get(Lang.Key.LIST_BUILTIN, sender, String.join(", ", lib.builtinIds())));
        List<String> custom = plugin.getNetwork().knownCustomIds();
        if (!custom.isEmpty()) {
            info(sender, Lang.get(Lang.Key.LIST_CUSTOM, sender, String.join(", ", custom)));
        }
        if (!lib.presetIds().isEmpty()) {
            info(sender, Lang.get(Lang.Key.LIST_PRESET, sender, String.join(", ", lib.presetIds())));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int info(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String name = effectName(ctx).toLowerCase(Locale.ROOT);
        EffectDef def = lookup(name);
        EffectLibrary.Preset preset = plugin.getLibrary().preset(name);
        if (def != null) {
            sender.sendMessage(Component.text(Lang.get(Lang.Key.INFO_HEADER, sender, def.id(),
                    def.custom() ? "resource pack" : "built-in"), NamedTextColor.AQUA));
            info(sender, Lang.get(Lang.Key.INFO_FLAGS, sender, Lang.yesNo(def.flashing(), sender),
                    Lang.yesNo(def.overHud(), sender), def.defaultIntensity()));
            info(sender, Lang.get(Lang.Key.INFO_PRIORITY, sender, plugin.getLibrary().priority(def.id())));
            for (EffectDef.ParamDef p : def.params()) {
                info(sender, Lang.get(Lang.Key.INFO_PARAM, sender, p.name(), p.index(), p.def(), p.min(), p.max()));
            }
            return Command.SINGLE_SUCCESS;
        }
        if (preset != null) {
            sender.sendMessage(Component.text(Lang.get(Lang.Key.INFO_HEADER, sender, preset.id(), "preset"),
                    NamedTextColor.AQUA));
            for (EffectLibrary.PresetLayer l : preset.layers()) {
                info(sender, Lang.get(Lang.Key.INFO_PRESET, sender, l.effectId(), l.intensity()));
            }
            return Command.SINGLE_SUCCESS;
        }
        error(sender, Lang.Key.EFFECT_NOT_FOUND, name);
        return 0;
    }

    private int reload(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        List<String> warnings = plugin.reloadAll();
        ok(sender, Lang.Key.RELOAD_OK, plugin.getLibrary().builtinCount(),
                plugin.getBridge().bindingCount(), plugin.getLibrary().presetIds().size());
        for (String w : warnings) {
            warn(sender, Lang.Key.RELOAD_WARN, w);
        }
        return Command.SINGLE_SUCCESS;
    }
}
