package ru.servermmo.hud;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;

/**
 * Раскладка панели HUD из глифов ресурспака.
 *
 * <p>Клиент рисует панель строками босс-бара и сам центрует каждую строку.
 * Центр любой строки - это offset/2 относительно центра экрана, и от ширины
 * самой строки он не зависит: поэтому текст встаёт по центру панели сам, а
 * подгонять строки под одну ширину не нужно.</p>
 *
 * <p>Отсюда же и вся арифметика отступов (подробно в {@link HudLayout}):
 * сдвиг двигает строку на <b>половину</b> своей величины, а ширина
 * интерфейса, от которой считается центр, зависит от разрешения окна и
 * масштаба GUI. Поэтому готовый сдвиг в конфиг не кладём — его считает
 * {@link HudLayout} по масштабу интерфейса игрока.</p>
 *
 * <p>Панель класса - подложка под все три строки: она живёт в первой
 * строке, а текст следующих двух ложится поверх неё (шаг между строками
 * босс-бара у клиента 10 + lineHeight = 19 px). Панель 256x96: ширину больше
 * нельзя (предел атласа шрифта клиента 256x256), поэтому она выше, а не
 * шире.</p>
 *
 * <p>Глифы пака и обычный текст смешиваются: цифры, значки и буквы LVXP
 * рисуются шрифтом servermmo:hud, а ник и название клана - ванильным шрифтом,
 * потому что в паке нет ни кириллицы, ни латиницы. Ширину ванильного текста
 * сервер знает лишь приблизительно (см. {@link #vanillaWidth}), поэтому
 * длинные ники могут стоять на пару пикселей не по центру.</p>
 */
final class HudText {

    /** Глифы шрифта ресурспака. */
    private static final Style GLYPHS = Style.style()
            .font(Key.key("servermmo", "hud"))
            .color(NamedTextColor.WHITE)
            .build();
    /**
     * Обычный текст: ванильный шрифт клиента, того же размера и цвета.
     *
     * <p>Шрифт указан явно, и это важно. Клиент раздаёт стиль по дереву
     * компонентов: {@code Component.visit} считает
     * {@code selfStyle = getStyle().applyTo(parentStyle)}, а
     * {@code Style.applyTo} берёт поле потомка, только если оно задано, —
     * иначе наследует родительское. Значит компонент без шрифта, приклеенный
     * к компоненту со шрифтом servermmo:hud, рисуется этим самым шрифтом, а в
     * нём нет ни кириллицы, ни латиницы: вместо ника получается полоса
     * пустых квадратиков. Явный minecraft:default наследование обрывает.</p>
     */
    private static final Style VANILLA = Style.style()
            .font(Key.key("minecraft", "default"))
            .color(NamedTextColor.WHITE)
            .build();

    /** Панели классов: 8 шт. */
    private static final int PANEL_BASE = 0xE500;
    /**
     * Диапазон команды {@code /hud offsety}: контент панели можно сдвинуть
     * вниз относительно картинки на -15..+15 px. У каждого сдвига свой
     * вариант глифа панели (та же картинка, другой ascent в паке).
     */
    static final int PANEL_OFFSET_RANGE = 15;
    /** Ромбы уровней игрока 1..100 (номер нарисован на ромбе, без «lvl»). */
    private static final int LEVEL_BASE = 0xE400;
    /** Щиты уровней клана 1..100 (номер нарисован на щите, без «lvl»). */
    private static final int CLAN_BASE = 0xE470;
    /** Снаряжение: сердце, меч, топор, посох, молот, коса, вилы, лук, копьё, щит. */
    private static final char GEAR_HEART = '\uE300';
    private static final char GEAR_SWORD = '\uE301';
    private static final char GEAR_SHIELD = '\uE309';
    /** Валюты и значки панели: монеты, коины, нанагемы, клан, свиток, боевые очки. */
    private static final char MONEY_COINS = '\uE310';
    private static final char MONEY_STAR = '\uE311';
    private static final char MONEY_GEM = '\uE312';
    private static final char MONEY_CLAN = '\uE313';
    /** Свиток перед ником в первой строке панели. */
    private static final char MONEY_SCROLL = '\uE314';
    /** Боевые очки: серебряная монета с двумя мечами. */
    private static final char MONEY_BATTLE = '\uE315';
    /** Слоты способностей: 8 шт. */
    private static final int ABILITY_BASE = 0xE320;

    /** Отступ текста от левого края панели, px. */
    private static final int PANEL_PAD_LEFT = 8;
    /** Зазор между значком и числом, px. */
    private static final int ICON_GAP = 4;
    /** Зазор между колонками строки, px. */
    private static final int COLUMN_GAP = 6;
    /**
     * Левая граница ромба уровня от края панели, px. По центру панели стоит
     * декор (шлем/кристалл), и при коротком нике ромб садился прямо на него.
     * Поэтому ромб держим правее этой границы, даже если ник короткий.
     */
    private static final int DIAMOND_MIN_X = 148;

    /** Сердце (здоровье) и меч (урон) - старые 8-пиксельные глифы. */
    static final char HEART = '\uE010';
    static final char SWORD = '\uE011';

    private static final char BLANK_6 = '\uE10A';
    private static final char BLANK_4 = '\uE101';
    private static final char BLANK_2 = '\uE100';
    private static final char BLANK_1 = '\uE110';

    /** Ступеней затухания индикатора урона (0 - полная яркость). */
    static final int FADE_STEPS = 5;
    /** База кодовых точек ступеней: ступень L - FADE_BASE + L*16 + позиция. */
    private static final int FADE_BASE = 0xE200;
    /**
     * Цифры хотбара: те же PNG, что и цифры панели, но отдельные кодовые
     * точки и ниже (ascent -1 в шрифте) — чтобы цифры строки экшн-бара стояли
     * по центру опущенных значков, а не выше них. Панель цифры не трогаем.
     * Порядок тот же, что в DIGIT_CHARS генератора: 0-9, /, пробел.
     */
    private static final int HOTBAR_DIGIT_BASE = 0xE600;
    /** База ступеней затухания для хотбара: ступень L - E610 + L*16 + позиция. */
    private static final int HOTBAR_FADE_BASE = 0xE610;

    private static final char SHIFT_RIGHT = '\uE110';
    private static final char SHIFT_LEFT = '\uE120';

    /**
     * Старший бит сдвига: глифы \uE110+i и \uE120+i дают +2^i и -2^i.
     *
     * <p>Бит 11 — это ±2048, то есть любой сдвиг до ±4095 px. Раньше хватало
     * 9 бита (±1023), но этого не достаёт до левого края на масштабе 1 и на
     * широком экране: интерфейс там бывает шире 2048 px.</p>
     */
    private static final int SHIFT_BITS = 11;

    private final Map<Character, Integer> advances;

    HudText(InputStream metrics) {
        this.advances = load(metrics);
    }

    private static Map<Character, Integer> load(InputStream stream) {
        Map<Character, Integer> map = new HashMap<>();
        // запасные значения: цифры 7 px, «1» 6 px, значки 32 px, панель 240 px
        for (char digit = '0'; digit <= '9'; digit++) {
            map.put(digit, 7);
        }
        map.put('1', 6);
        map.put('/', 7);
        map.put(' ', 4);
        for (int i = 0; i < 12; i++) {
            map.put((char) (HOTBAR_DIGIT_BASE + i), i == 1 ? 6 : 7);
        }
        for (int step = 0; step < FADE_STEPS; step++) {
            int base = HOTBAR_FADE_BASE + step * 16;
            for (int digit = 0; digit <= 9; digit++) {
                map.put((char) (base + digit), digit == 1 ? 6 : 7);
            }
            map.put((char) (base + 10), 7);
            map.put((char) (base + 11), 8);
        }
        for (char letter : "LVXP".toCharArray()) {
            map.put(letter, 7);
        }
        map.put(HEART, 8);
        map.put(SWORD, 8);
        for (int i = 0; i < 10; i++) {
            map.put((char) (0xE300 + i), 20);
        }
        for (int i = 0; i < 6; i++) {
            map.put((char) (0xE310 + i), 14);
        }
        for (int i = 0; i < 8; i++) {
            map.put((char) (ABILITY_BASE + i), 20);
        }
        for (int i = 0; i < 100; i++) {
            map.put((char) (LEVEL_BASE + i), 14);
        }
        for (int i = 0; i < 100; i++) {
            map.put((char) (CLAN_BASE + i), 14);
        }
        for (int i = 0; i < (2 * PANEL_OFFSET_RANGE + 1) * 8; i++) {
            map.put((char) (PANEL_BASE + i), 256);
        }
        for (int step = 0; step < FADE_STEPS; step++) {
            int base = FADE_BASE + step * 16;
            for (int digit = 0; digit <= 9; digit++) {
                map.put((char) (base + digit), digit == 1 ? 6 : 7);
            }
            map.put((char) (base + 10), 7);
            map.put((char) (base + 11), 8);
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
                        // битая строка - останется запасное значение
                    }
                }
            }
        } catch (IOException ignored) {
            // не смогли прочитать - работаем по запасной таблице
        }
        return map;
    }

    int width(char ch) {
        return advances.getOrDefault(ch, 7);
    }

    int width(String text) {
        int total = 0;
        for (int i = 0; i < text.length(); i++) {
            total += width(text.charAt(i));
        }
        return total;
    }

    /**
     * Приблизительная ширина строки ванильным шрифтом (8 px).
     *
     * <p>Точных метрик у сервера нет, поэтому узкие символы считаем по 2 px,
     * пробел 4, остальные 6. Ошибка в несколько пикселей лишь сдвигает ник
     * внутри панели, но не ломает раскладку.</p>
     */
    int vanillaWidth(String text) {
        int total = 0;
        for (int i = 0; i < text.length(); i++) {
            total += vanillaCharWidth(text.charAt(i));
        }
        return total;
    }

    private static int vanillaCharWidth(char ch) {
        if (ch == ' ') {
            return 4;
        }
        if ("il.,!:;'|".indexOf(ch) >= 0) {
            return 2;
        }
        if ("tfr()[]{}".indexOf(ch) >= 0) {
            return 5;
        }
        return 6;
    }

    /**
     * Заголовок первой строки: ник и класс, подогнанные под ширину панели.
     *
     * <p>Ник в Minecraft — до 16 символов, а панель узкая: длинный ник с
     * классом не влезает, и строка вылезала за пластину (хвост уходил в
     * минус, и клиент центровал строку шире панели). Поэтому ник режем
     * справа, пока заголовок не влезет в отведённую ему ширину; класс не
     * трогаем — он короткий. Так строка всегда ровно в ширину панели, и
     * хвост неотрицателен.</p>
     */
    private String fitTitle(String playerName, String className, int panelWidth, char diamond,
                            String xpFraction) {
        String suffix = " · " + className;
        int budget = panelWidth - PANEL_PAD_LEFT - width(MONEY_SCROLL) - ICON_GAP
                - COLUMN_GAP - width(diamond) - COLUMN_GAP - width(xpFraction)
                - vanillaWidth(suffix);
        String name = playerName == null ? "" : playerName;
        while (vanillaWidth(name) > budget && !name.isEmpty()) {
            name = name.substring(0, name.length() - 1);
        }
        return name + suffix;
    }

    /** Ширина глифа-панели класса: по ней считается сдвиг всей панели. */
    int panelWidth(int classIndex) {
        return width((char) (PANEL_BASE + Math.max(0, Math.min(7, classIndex))));
    }

    /**
     * Три строки панели.
     *
     * <p>Первая - подложка-панель и поверх неё свиток, ник с классом, ромб
     * уровня (номер нарисован на нём) и опыт дробью, вторая - клан: щит
     * уровня, название и опыт клана дробью, третья - четыре валюты.</p>
     *
     * <p>Все три строки получают один и тот же сдвиг {@code P}, поэтому их
     * центры совпадают (центр строки всегда в {@code W/2 + P/2}), а левый
     * край панели стоит ровно в {@code margin-left} от края экрана — см.
     * {@link HudLayout}. Ставить сдвиг в конфиг нельзя: он в пикселях
     * интерфейса и при смене масштаба GUI меняется в разы.</p>
     *
     * @param layout геометрия под экран игрока (ширина интерфейса и отступ)
     * @param offsetY сдвиг текста и значков вниз относительно картинки
     *                панели, px (-15..15, команда {@code /hud offsety})
     */
    Component[] panel(HudProfile profile, String playerName, long level, long xp, long xpToNext,
                      HudLayout layout, int offsetY) {
        int variant = Math.max(0, Math.min(2 * PANEL_OFFSET_RANGE,
                offsetY + PANEL_OFFSET_RANGE));
        char panelGlyph = (char) (PANEL_BASE + variant * 8 + profile.classIndex());
        int panelWidth = width(panelGlyph);
        int offset = layout.panelShift(panelWidth);

        // Строка 1: подложка, затем возврат курсора в её начало и текст поверх.
        // В конце - хвост до ширины панели: иначе клиент центрует строку по
        // длине ника и ставит подложку не там, где остальные две строки.
        String xpFraction = xp + "/" + xpToNext;
        char diamond = (char) (LEVEL_BASE + levelIndex(level));
        String title = fitTitle(playerName, profile.className(), panelWidth, diamond, xpFraction);
        // Ромб — правее центрального декора, даже при коротком нике: зазор
        // перед ним растягиваем до DIAMOND_MIN_X.
        int titleEnd = PANEL_PAD_LEFT + width(MONEY_SCROLL) + ICON_GAP + vanillaWidth(title);
        int gapBeforeDiamond = Math.max(COLUMN_GAP, DIAMOND_MIN_X - titleEnd);
        int tail = panelWidth - titleEnd - gapBeforeDiamond - width(diamond)
                - COLUMN_GAP - width(xpFraction);

        Component first = Component.text(shift(offset), GLYPHS)
                .append(Component.text(String.valueOf(panelGlyph), GLYPHS))
                .append(Component.text(shift(-(panelWidth - PANEL_PAD_LEFT)), GLYPHS))
                .append(Component.text(MONEY_SCROLL + pad(ICON_GAP), GLYPHS))
                .append(Component.text(title, VANILLA))
                .append(Component.text(pad(gapBeforeDiamond) + diamond + pad(COLUMN_GAP)
                        + xpFraction, GLYPHS))
                .append(Component.text(pad(tail), GLYPHS));

        // Строка 2: клан - щит уровня, название и опыт клана дробью.
        String clanPrefix = shift(offset)
                + (char) (CLAN_BASE + levelIndex(profile.clanLevel())) + pad(COLUMN_GAP);
        String clanSuffix = pad(COLUMN_GAP)
                + profile.clanXp() + "/" + profile.clanXpToNext();
        Component second = Component.text(clanPrefix, GLYPHS)
                .append(Component.text(
                        profile.clanName().isEmpty() ? "Без клана" : profile.clanName(), VANILLA))
                .append(Component.text(clanSuffix, GLYPHS));

        // Строка 3: четыре валюты - значок и число.
        String money = "" + MONEY_COINS + pad(ICON_GAP) + profile.coins() + pad(COLUMN_GAP)
                + MONEY_STAR + pad(ICON_GAP) + profile.starCoins() + pad(COLUMN_GAP)
                + MONEY_GEM + pad(ICON_GAP) + profile.nanogems() + pad(COLUMN_GAP)
                + MONEY_BATTLE + pad(ICON_GAP) + profile.battlePoints();
        Component third = Component.text(shift(offset) + pad(PANEL_PAD_LEFT) + money, GLYPHS);

        return new Component[]{first, second, third};
    }

    /**
     * Строка хотбара (экшн-бар): здоровье, урон и защита у левого края
     * хотбара, а справа от самого хотбара - квадраты способностей.
     *
     * @param anchorX левый край строки относительно центра экрана, px
     *                (-91 - левый край хотбара)
     * @param fadeStep ступень затухания индикатора, -1 - индикатора нет
     * @param abilitySlots сколько квадратов способностей рисовать
     * @param layout геометрия под экран игрока
     */
    Component hotbar(long health, long damage, long armor, long lastDamage, int fadeStep,
                     int anchorX, int abilitySlots, HudLayout layout) {
        String body = "" + GEAR_HEART + pad(ICON_GAP) + hotbarDigits(health) + pad(COLUMN_GAP)
                + GEAR_SWORD + pad(ICON_GAP) + hotbarDigits(damage) + pad(COLUMN_GAP)
                + GEAR_SHIELD + pad(ICON_GAP) + hotbarDigits(armor);
        if (fadeStep >= 0) {
            body += pad(COLUMN_GAP) + indicator(lastDamage, fadeStep);
        }
        // Якорь удваивается: сдвиг двигает строку лишь наполовину своей
        // величины, потому что клиент центрует строку по её суммарной ширине.
        StringBuilder line = new StringBuilder(
                shift(HudLayout.anchoredShift(anchorX, width(body), layout.interfaceWidth())));
        line.append(body);
        // Правый край хотбара - это +91 от центра; способности встают за ним.
        int slots = Math.max(0, Math.min(8, abilitySlots));
        if (slots > 0) {
            int bodyEnd = anchorX + width(body);
            line.append(pad(Math.max(0, 91 + 6 - bodyEnd)));
            for (int i = 0; i < slots; i++) {
                line.append((char) (ABILITY_BASE + i));
            }
        }
        return Component.text(line.toString(), GLYPHS);
    }

    /** «-25♥» глифами ступени затухания (0 - полная яркость). */
    String indicator(long amount, int fadeStep) {
        int step = Math.max(0, Math.min(FADE_STEPS - 1, fadeStep));
        int base = HOTBAR_FADE_BASE + step * 16;
        StringBuilder builder = new StringBuilder();
        builder.append((char) (base + 10)); // минус
        String digits = Long.toString(Math.max(0L, amount));
        for (int i = 0; i < digits.length(); i++) {
            builder.append((char) (base + (digits.charAt(i) - '0')));
        }
        builder.append((char) (base + 11)); // сердце
        return builder.toString();
    }

    /**
     * Число глифами цифр хотбара (те же PNG, что и цифры панели, но ниже —
     * по центру опущенных значков). Панельные цифры не трогаем.
     */
    String hotbarDigits(long value) {
        String digits = Long.toString(value);
        StringBuilder builder = new StringBuilder(digits.length());
        for (int i = 0; i < digits.length(); i++) {
            builder.append((char) (HOTBAR_DIGIT_BASE + (digits.charAt(i) - '0')));
        }
        return builder.toString();
    }

    /** Номер глифа-значка для уровня: уровень 1 - первый, 100 - последний. */
    private static int levelIndex(long level) {
        long index = Math.max(1L, level) - 1L;
        return (int) Math.min(99L, index);
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

    /** Сдвиг курсора на {@code pixels}: положительный - вправо, отрицательный - влево. */
    String shift(int pixels) {
        int value = Math.max(-HudLayout.MAX_SHIFT, Math.min(HudLayout.MAX_SHIFT, pixels));
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
