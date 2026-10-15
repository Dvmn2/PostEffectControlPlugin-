package net.dvmn2.posteffectcontrolplugin;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Одно «назначение» эффекта игроку: простой эффект (1 слой) или пресет (несколько слоёв).
 * Повторная выдача с тем же source заменяет предыдущее назначение.
 *
 * @param endMillis абсолютное время окончания (epoch ms) или -1 — бессрочно
 */
public record Applied(String source, List<Layer> layers, float intensity, long endMillis, int fadeInMs, int fadeOutMs) {

    /** Значение цвета «не задан»: клиент возьмёт цвет по умолчанию из описания эффекта в ресурспаке. */
    public static final int COLOR_UNSET = -1;

    /**
     * Слой: конкретный эффект с параметрами. params всегда длины {@link EffectDef#PARAM_COUNT}.
     * <p>
     * Встроенные эффекты разворачиваются в готовые значения при выдаче. Для эффектов из ресурспаков
     * значения по умолчанию заранее неизвестны (их знает только клиент), поэтому незаданные значения
     * хранятся как {@code color = COLOR_UNSET} и {@code NaN} в params, а параметры, заданные по имени,
     * лежат в {@code named} и превращаются в индексы при отправке — по описанию, которое прислал клиент.
     */
    public record Layer(String effectId, float intensity, int color, float[] params, Map<String, Float> named) {
    }

    public static float[] unsetParams() {
        float[] values = new float[EffectDef.PARAM_COUNT];
        Arrays.fill(values, Float.NaN);
        return values;
    }

    public boolean infinite() {
        return endMillis < 0;
    }

    public boolean expired(long now) {
        return endMillis >= 0 && now >= endMillis;
    }

    /** Подпись для сравнения «изменилось ли что-то, что нужно слать клиенту» (без оставшегося времени). */
    public String signature() {
        StringBuilder sb = new StringBuilder();
        sb.append(source).append('|').append(intensity).append('|').append(endMillis)
                .append('|').append(fadeInMs).append('|').append(fadeOutMs);
        for (Layer l : layers) {
            sb.append('[').append(l.effectId()).append(',').append(l.intensity()).append(',').append(l.color());
            for (float p : l.params()) {
                sb.append(',').append(String.format(Locale.ROOT, "%.4f", p));
            }
            sb.append(new TreeMap<>(l.named()));
            sb.append(']');
        }
        return sb.toString();
    }
}
