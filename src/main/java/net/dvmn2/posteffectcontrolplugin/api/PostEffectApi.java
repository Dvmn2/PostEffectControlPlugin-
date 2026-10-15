package net.dvmn2.posteffectcontrolplugin.api;

import net.dvmn2.posteffectcontrolplugin.ApplyOptions;
import org.bukkit.entity.Player;

/**
 * API для других плагинов. Получение:
 * <pre>
 * PostEffectApi api = Bukkit.getServicesManager().load(PostEffectApi.class);
 * </pre>
 * Все методы вызывать из основного потока сервера.
 */
public interface PostEffectApi {

    /**
     * Выдаёт эффект или пресет игроку.
     *
     * @param name           id встроенного эффекта или пресета ("freeze") либо эффекта из ресурспака клиента
     *                       в виде "namespace:path" ("example:tint"); такой эффект должен быть у клиента в ресурсах
     * @param durationMillis длительность в мс; отрицательное значение — бессрочно
     * @param options        необязательные параметры (null = по умолчанию)
     * @return false, если эффект неизвестен или у игрока слишком много слоёв
     */
    boolean apply(Player player, String name, long durationMillis, ApplyOptions options);

    /** Снимает эффект/пресет. @return true, если он был у игрока. */
    boolean remove(Player player, String name);

    /** Снимает все эффекты игрока. */
    void clear(Player player);

    /** Есть ли у игрока клиентский мод (эффекты видны только с ним). */
    boolean hasMod(Player player);
}
