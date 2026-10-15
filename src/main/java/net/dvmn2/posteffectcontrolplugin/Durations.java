package net.dvmn2.posteffectcontrolplugin;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Разбор длительностей: 30, 30s, 5m, 1h30m, 500ms, inf. */
public final class Durations {

    private static final Pattern PART = Pattern.compile("(\\d+(?:\\.\\d+)?)(ms|s|m|h|d)?");

    private Durations() {
    }

    /** @return миллисекунды или -1 для бессрочно. */
    public static long parse(String text) {
        String s = text.trim().toLowerCase(Locale.ROOT);
        if (s.equals("inf") || s.equals("infinite") || s.equals("forever") || s.equals("-")) {
            return -1L;
        }
        if (s.isEmpty()) {
            throw new IllegalArgumentException(text);
        }
        Matcher m = PART.matcher(s);
        int pos = 0;
        double total = 0;
        while (pos < s.length()) {
            if (!m.find(pos) || m.start() != pos) {
                throw new IllegalArgumentException(text);
            }
            double value = Double.parseDouble(m.group(1));
            String unit = m.group(2) == null ? "s" : m.group(2);
            total += value * switch (unit) {
                case "ms" -> 1.0;
                case "s" -> 1000.0;
                case "m" -> 60_000.0;
                case "h" -> 3_600_000.0;
                default -> 86_400_000.0;
            };
            pos = m.end();
        }
        if (total > 31L * 86_400_000L) {
            throw new IllegalArgumentException(text);
        }
        return Math.round(total);
    }

    public static String format(long millis) {
        if (millis < 0) {
            return "∞";
        }
        long totalSeconds = (millis + 999) / 1000;
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0) {
            sb.append(h).append("h");
        }
        if (m > 0) {
            sb.append(m).append("m");
        }
        if (s > 0 || sb.length() == 0) {
            sb.append(s).append("s");
        }
        return sb.toString();
    }
}
