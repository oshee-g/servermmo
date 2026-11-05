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
    /** Ступеней затухания индикатора урона (0 — полная яркость). */
    static final int FADE_STEPS = 5;
    /** База кодовых точек ступеней: ступень L — FADE_BASE + L*16 + позиция. */
    private static final int FADE_BASE = 0xE200;

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
        // ступени затухания индикатора урона: цифры 6 («1» — 5), минус 6, сердце 7
        for (int step = 0; step < FADE_STEPS; step++) {
            int base = FADE_BASE + step * 16;
            for (int digit = 0; digit <= 9; digit++) {
                map.put((char) (base + digit), digit == 1 ? 5 : 6);
            }
            map.put((char) (base + 10), 6);
            map.put((char) (base + 11), 7);
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
    String[] panel(long damage, long level, long xp, long xpToNext, int offset) {
        // Здоровье переехало в хотбар (строка экшн-бара), наверху остались
        // урон, уровень и опыт.
        String first = block(String.valueOf(SWORD), Long.toString(damage));
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

    /**
     * Строка хотбара (экшн-бар): сердце и текущее здоровье, а справа —
     * индикатор полученного урона «−25♥» нужной ступени затухания.
     *
     * <p>Экшн-бар клиент тоже центрирует. Сердце должно стоять у левого края
     * хотбара (там, где были ванильные сердца), поэтому сдвиг считается от
     * полной ширины строки: с появлением индикатора сердце не «прыгает».</p>
     *
     * @param anchorX левый край строки относительно центра экрана, px
     *                (-91 — левый край хотбара)
     * @param fadeStep ступень затухания индикатора, -1 — индикатора нет
     */
    String hotbar(long health, long lastDamage, int fadeStep, int anchorX) {
        String body = block(String.valueOf(HEART), Long.toString(health));
        if (fadeStep >= 0) {
            body += pad(BLOCK_GAP) + indicator(lastDamage, fadeStep);
        }
        int shift = anchorX + width(body) / 2;
        return shift(shift) + body;
    }

    /** «−25♥» глифами ступени затухания (0 — полная яркость). */
    String indicator(long amount, int fadeStep) {
        int step = Math.max(0, Math.min(FADE_STEPS - 1, fadeStep));
        int base = FADE_BASE + step * 16;
        StringBuilder builder = new StringBuilder();
        builder.append((char) (base + 10)); // минус
        String digits = Long.toString(Math.max(0L, amount));
        for (int i = 0; i < digits.length(); i++) {
            builder.append((char) (base + (digits.charAt(i) - '0')));
        }
        builder.append((char) (base + 11)); // сердце
        return builder.toString();
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
