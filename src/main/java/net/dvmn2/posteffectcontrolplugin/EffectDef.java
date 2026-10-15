package net.dvmn2.posteffectcontrolplugin;

import java.util.List;

/**
 * Описание эффекта: встроенного (шейдер лежит в моде) или из ресурспака клиента.
 * Описание эффекта из ресурспака приходит от клиента (pe_caps) — это данные клиента, им нельзя слепо доверять.
 * <p>
 * Контракт параметров одинаков для всех шейдеров: color (RGB) и до 8 float-параметров p0..p7.
 */
public record EffectDef(String id,
                        boolean custom,
                        String displayName,
                        boolean flashing,
                        boolean overHud,
                        float defaultIntensity,
                        int defaultColor,
                        List<ParamDef> params) {

    public static final int PARAM_COUNT = 8;

    /** Именованный параметр. index = номер pN, на который он отображается. */
    public record ParamDef(String name, int index, float def, float min, float max) {
    }

    public float[] defaultParams() {
        float[] values = new float[PARAM_COUNT];
        for (ParamDef p : params) {
            if (p.index() >= 0 && p.index() < PARAM_COUNT) {
                values[p.index()] = p.def();
            }
        }
        return values;
    }

    public ParamDef param(String name) {
        for (ParamDef p : params) {
            if (p.name().equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }

    public ParamDef paramByIndex(int index) {
        for (ParamDef p : params) {
            if (p.index() == index) {
                return p;
            }
        }
        return null;
    }
}
