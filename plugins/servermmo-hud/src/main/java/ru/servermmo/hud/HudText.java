package ru.servermmo.hud;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Раскладка панели HUD из глифов ресурспака.
 *
 * <p>Клиент рисует панель строками босс-бара и сам центрует каждую строку.
 * Чтобы строки не «плясали» друг относительно друга, обе строки добиваются
 * пустыми глифами до одинаковой ширины: тогда клиент считает их ширина
 * равными и ставит на одну вертикаль.</p>
 *
 * <p>Ширина глифа в bitmap-провайдере — последний непрозрачный пиксель + 1,
 * поэтому таблицу ширин считает {@code tools/gen_font_metrics.py} по самим PNG
 * и кладёт в {@code font-metrics.properties}. Значения ниже — только запасной
 * вариант, если файла нет.</p>
 */
final class HudText {

    /** Ширина колонки значка или подписи, px. */
    static final int COLUMN_WIDTH = 18;
    /** Зазор между значком и числом, px. */
    static final int COLUMN_GAP = 2;
    /** Щель между левым и правым блоками панели, px. */
    static final int BLOCK_GAP = 8;

    /** Сердце (здоровье). */
    static final char HEART = '\uE010';
    /** Меч (урон). */
    static final char SWORD = '\uE011';

    private static final char BLANK_6 = '\uE10A';
    private static final char BLANK_4 = '\uE101';
    private static final char BLANK_2 = '\uE100';
    private static final char BLANK_1 = '\uE110';
    private static final char SHIFT_RIGHT = '\uE110';
    private static final char SHIFT_LEFT = '\uE120';
    private static final int SHIFT_BITS = 9;

    private final Map<Character, Integer> advances;

    HudText(InputStream metrics) {
        this.advances = load(metrics);
    }

    private static Map<Character, Integer> load(InputStream stream) {
        Map<Character, Integer> map = new HashMap<>();
        // запасные значения: цифры 6 px, «1» 5 px, иконки 7 px
        for (char digit = '0'; digit <= '9'; digit++) {
            map.put(digit, 6);
        }
        map.put('1', 5);
        map.put('/', 6);
        map.put(' ', 1);
        for (char letter : "LVXP".toCharArray()) {
            map.put(letter, 6);
        }
        for (char icon : new char[]{HEART, SWORD, '\uE013'}) {
            map.put(icon, 7);
        }
        if (stream == null) {
            return map;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("advances=")) {
                    continue;
                }
                for (String pair : line.substring("advances=".length()).split(",")) {
                    int colon = pair.indexOf(':');
                    if (colon <= 0) {
                        continue;
                    }
                    try {
                        char ch = (char) Integer.parseInt(pair.substring(0, colon).trim(), 16);
                        map.put(ch, Integer.parseInt(pair.substring(colon + 1).trim()));
                    } catch (NumberFormatException ignored) {
                        // битая строка — останется запасное значение
                    }
                }
            }
        } catch (IOException ignored) {
            // не смогли прочитать — работаем по запасной таблице
        }
        return map;
    }

    int width(char ch) {
        return advances.getOrDefault(ch, 6);
    }

    int width(String text) {
        int total = 0;
        for (int i = 0; i < text.length(); i++) {
            total += width(text.charAt(i));
        }
        return total;
    }

    /**
     * Обе строки панели, выровненные до одной ширины.
     *
     * <p>{@code offset} — сдвиг всей панели в пикселях интерфейса: отрицательный
     * уводит её влево. Клиент центрует строку, поэтому сдвиг и есть положение
     * панели на экране.</p>
     *
     * @return строка «здоровье/урон» и строка «уровень/опыт»
     */
    String[] panel(long health, long damage, long level, long xp, long xpToNext, int offset) {
        String first = block(String.valueOf(HEART), Long.toString(health))
                + pad(BLOCK_GAP)
                + block(String.valueOf(SWORD), Long.toString(damage));
        String second = block("LVL", Long.toString(level))
                + pad(BLOCK_GAP)
                + block("XP", xp + "/" + xpToNext);

        int width = Math.max(width(first), width(second));
        String shift = shift(offset);
        return new String[]{
                shift + first + pad(width - width(first)),
                shift + second + pad(width - width(second)),
        };
    }

    /** Колонка значка или подписи слева плюс число справа от неё. */
    private String block(String label, String value) {
        return pad(COLUMN_WIDTH - width(label)) + label + pad(COLUMN_GAP) + value;
    }

    /** Пустые глифы ровно на {@code pixels} пикселей. */
    String pad(int pixels) {
        StringBuilder builder = new StringBuilder();
        int left = Math.max(0, pixels);
        for (int blank : new int[]{6, 4, 2, 1}) {
            while (left >= blank) {
                builder.append(blankGlyph(blank));
                left -= blank;
            }
        }
        return builder.toString();
    }

    private static char blankGlyph(int width) {
        return switch (width) {
            case 6 -> BLANK_6;
            case 4 -> BLANK_4;
            case 2 -> BLANK_2;
            default -> BLANK_1;
        };
    }

    /** Сдвиг курсора на {@code pixels}: положительный — вправо, отрицательный — влево. */
    String shift(int pixels) {
        // В паке есть сдвиги до 1023 px — для панели этого хватает с запасом.
        int value = Math.max(-1023, Math.min(1023, pixels));
        if (value == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        int left = Math.abs(value);
        char base = value < 0 ? SHIFT_LEFT : SHIFT_RIGHT;
        for (int bit = SHIFT_BITS; bit >= 0; bit--) {
            if ((left & (1 << bit)) != 0) {
                builder.append((char) (base + bit));
            }
        }
        return builder.toString();
    }
}
