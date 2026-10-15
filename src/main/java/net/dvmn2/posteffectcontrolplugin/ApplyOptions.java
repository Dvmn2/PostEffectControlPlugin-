package net.dvmn2.posteffectcontrolplugin;

import java.util.HashMap;
import java.util.Map;

/**
 * Необязательные параметры выдачи. null = «взять значение по умолчанию эффекта/конфига».
 * Для пресетов color и параметры игнорируются (у слоёв пресета они заданы в конфиге).
 */
public final class ApplyOptions {

    public Float intensity;
    public Integer color;
    public Integer fadeInMs;
    public Integer fadeOutMs;
    /** Параметры по индексу pN. */
    public final Map<Integer, Float> paramsByIndex = new HashMap<>();
    /** Параметры по имени из описания эффекта. */
    public final Map<String, Float> paramsByName = new HashMap<>();

    public static ApplyOptions defaults() {
        return new ApplyOptions();
    }
}
