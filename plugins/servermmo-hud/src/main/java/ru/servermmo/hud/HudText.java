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
 * Поэтому строки дополняются пробелами до ширины подложки: центр остаётся
 * общим, а текст можно начать с отдельной координаты под эмблемой каждого
 * класса.</p>
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
 * <p>Весь текст панели — свои глифы шрифта servermmo:hud (атлас hud_text.png:
 * кириллица, латиница, цифры, знаки), поэтому текст можно двигать отдельно
 * от значков (команда /hud texty). Ширины глифов берутся из
 * font-metrics.properties, как у значков.</p>
 */
final class HudText {

    /** Глифы шрифта ресурспака. */
    private static final Style GLYPHS = Style.style()
            .font(Key.key("servermmo", "hud"))
            .color(NamedTextColor.WHITE)
            .build();
    /** Панели классов: по одному глифу на класс в каждом варианте offsety. */
    private static final int PANEL_CLASS_COUNT = HudProfile.CLASSES.length;
    /** Диапазоны точных смещений команд, в пикселях интерфейса. */
    static final int PANEL_OFFSET_RANGE = 100;
    static final int ICON_OFFSET_RANGE = 100;
    static final int TEXT_X_OFFSET_RANGE = 100;
    private static final int VARIANT_COUNT = 2 * ICON_OFFSET_RANGE + 1;
    private static final int PUA_START = 0xF0000;
    private static final int PUA_FIRST_PLANE_COUNT = 0xFFFFE - PUA_START;
    /**
     * Текст панели своими глифами (атлас hud_text.png): кириллица, латиница,
     * цифры и знаки. Текст и мелкие значки/цифры используют варианты высоты
     * для команды texty; большая эмблема панели остаётся на месте.
     */
    private static final String TEXT_CHARS =
            "АБВГДЕЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ"
            + "абвгдежзийклмнопрстуфхцчшщъыьэюя"
            + "Ёё"
            + "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
            + "abcdefghijklmnopqrstuvwxyz"
            + "0123456789"
            + "_/-·.,!?:;()[]{}+=*#@%&|<>"
            + "—–«»№°±×÷§`'\"\\~^$";
    /** Inline-варианты: цифры, валюты, уровни и уровни клана. */
    private static final int INLINE_GLYPH_COUNT = 218;
    private static final int INLINE_MONEY_OFFSET = 12;
    private static final int INLINE_LEVEL_OFFSET = 18;
    private static final int INLINE_CLAN_OFFSET = 118;
    private static final int INLINE_START_INDEX = 0;
    private static final int INLINE_VARIANT_TOTAL = VARIANT_COUNT * INLINE_GLYPH_COUNT;
    private static final int PANEL_START_INDEX = INLINE_VARIANT_TOTAL;
    private static final int PANEL_VARIANT_TOTAL = VARIANT_COUNT * PANEL_CLASS_COUNT;
    private static final int TEXT_START_INDEX = PANEL_START_INDEX + PANEL_VARIANT_TOTAL;
    private static final int TEXT_VARIANT_TOTAL = VARIANT_COUNT * TEXT_CHARS.length();
    private static final int TEXT_SMALL_START_INDEX = TEXT_START_INDEX + TEXT_VARIANT_TOTAL;
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

    /**
     * Полоса текста новых панелей: эмблема занимает левую часть, строки HUD
     * идут справа от неё. Координаты проверены на панели 256x96.
     */
    private static final int CONTENT_LEFT = 66;
    private static final int CONTENT_RIGHT = 244;
    /** Зазор между значком и числом, px. */
    private static final int ICON_GAP = 4;
    /** Зазор между колонками строки, px. */
    private static final int COLUMN_GAP = 6;

    /** Сердце (здоровье) и меч (урон) - старые 8-пиксельные глифы. */
    static final char HEART = '\uE010';
    static final char SWORD = '\uE011';

    private static final char BLANK_6 = '\uE10A';
    private static final char BLANK_4 = '\uE101';
    private static final char BLANK_2 = '\uE100';
    private static final char BLANK_1 = '\uE110';

    /** Ступеней затухания индикатора урона (0 - полная яркость). */
    static final int FADE_STEPS = 5;
    /** supplementary-PUA layout synchronized with tools/gen_resourcepack.py. */
    private static final int HOTBAR_LEFT_GEAR_COUNT = 3;
    private static final int HOTBAR_LEFT_DIGIT_COUNT = 10;
    private static final int HOTBAR_LEFT_GEAR_OFFSET = 0;
    private static final int HOTBAR_LEFT_DIGIT_OFFSET = HOTBAR_LEFT_GEAR_COUNT;
    private static final int HOTBAR_LEFT_FADE_OFFSET =
            HOTBAR_LEFT_GEAR_COUNT + HOTBAR_LEFT_DIGIT_COUNT;
    private static final int HOTBAR_LEFT_VARIANT_GLYPH_COUNT =
            HOTBAR_LEFT_FADE_OFFSET + FADE_STEPS * 12;
    private static final int HOTBAR_LEFT_START_INDEX =
            TEXT_SMALL_START_INDEX + TEXT_VARIANT_TOTAL;
    private static final int HOTBAR_LEFT_VARIANT_TOTAL =
            VARIANT_COUNT * HOTBAR_LEFT_VARIANT_GLYPH_COUNT;
    private static final int HOTBAR_RIGHT_START_INDEX =
            HOTBAR_LEFT_START_INDEX + HOTBAR_LEFT_VARIANT_TOTAL;
    private static final int HOTBAR_RIGHT_VARIANT_GLYPH_COUNT = 8;
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

    private final Map<Integer, Integer> advances;

    HudText(InputStream metrics) {
        this.advances = load(metrics);
    }

    private static Map<Integer, Integer> load(InputStream stream) {
        Map<Integer, Integer> map = new HashMap<>();
        // запасные значения: цифры 7 px, «1» 6 px, значки 32 px, панель 240 px
        for (char digit = '0'; digit <= '9'; digit++) {
            map.put((int) digit, 7);
        }
        map.put((int) '1', 6);
        map.put((int) '/', 7);
        map.put((int) ' ', 4);
        for (int i = 0; i < 12; i++) {
            map.put((int) (HOTBAR_DIGIT_BASE + i), i == 1 ? 6 : 7);
        }
        for (int step = 0; step < FADE_STEPS; step++) {
            int base = HOTBAR_FADE_BASE + step * 16;
            for (int digit = 0; digit <= 9; digit++) {
                map.put((int) (base + digit), digit == 1 ? 6 : 7);
            }
            map.put((int) (base + 10), 7);
            map.put((int) (base + 11), 8);
        }
        for (char letter : "LVXP".toCharArray()) {
            map.put((int) letter, 7);
        }
        map.put((int) HEART, 8);
        map.put((int) SWORD, 8);
        for (int i = 0; i < 10; i++) {
            map.put((int) (0xE300 + i), 20);
        }
        for (int i = 0; i < 6; i++) {
            map.put((int) (0xE310 + i), 14);
        }
        for (int i = 0; i < 8; i++) {
            map.put((int) (ABILITY_BASE + i), 20);
        }
        for (int i = 0; i < 100; i++) {
            map.put((int) (LEVEL_BASE + i), 14);
        }
        for (int i = 0; i < 100; i++) {
            map.put((int) (CLAN_BASE + i), 14);
        }
        for (int variant = 0; variant < VARIANT_COUNT; variant++) {
            int inlineStart = INLINE_START_INDEX + variant * INLINE_GLYPH_COUNT;
            for (int i = 0; i < INLINE_GLYPH_COUNT; i++) {
                int advance = i < INLINE_MONEY_OFFSET ? 7 : 14;
                map.put(puaCodePoint(inlineStart + i), advance);
            }
            int panelStart = PANEL_START_INDEX + variant * PANEL_CLASS_COUNT;
            for (int i = 0; i < PANEL_CLASS_COUNT; i++) {
                map.put(puaCodePoint(panelStart + i), 256);
            }
            int textStart = TEXT_START_INDEX + variant * TEXT_CHARS.length();
            int smallStart = TEXT_SMALL_START_INDEX + variant * TEXT_CHARS.length();
            for (int i = 0; i < TEXT_CHARS.length(); i++) {
                map.put(puaCodePoint(textStart + i), 16);
                map.put(puaCodePoint(smallStart + i), 8);
            }
            int hotbarLeftStart = HOTBAR_LEFT_START_INDEX
                    + variant * HOTBAR_LEFT_VARIANT_GLYPH_COUNT;
            for (int i = 0; i < HOTBAR_LEFT_GEAR_COUNT; i++) {
                map.put(puaCodePoint(hotbarLeftStart + HOTBAR_LEFT_GEAR_OFFSET + i), 20);
            }
            for (int i = 0; i < HOTBAR_LEFT_DIGIT_COUNT; i++) {
                map.put(puaCodePoint(hotbarLeftStart + HOTBAR_LEFT_DIGIT_OFFSET + i),
                        i == 1 ? 6 : 7);
            }
            for (int step = 0; step < FADE_STEPS; step++) {
                int fadeStart = hotbarLeftStart + HOTBAR_LEFT_FADE_OFFSET + step * 12;
                for (int digit = 0; digit <= 9; digit++) {
                    map.put(puaCodePoint(fadeStart + digit), digit == 1 ? 6 : 7);
                }
                map.put(puaCodePoint(fadeStart + 10), 7);
                map.put(puaCodePoint(fadeStart + 11), 8);
            }
            int hotbarRightStart = HOTBAR_RIGHT_START_INDEX
                    + variant * HOTBAR_RIGHT_VARIANT_GLYPH_COUNT;
            for (int i = 0; i < HOTBAR_RIGHT_VARIANT_GLYPH_COUNT; i++) {
                map.put(puaCodePoint(hotbarRightStart + i), 20);
            }
        }
        for (int step = 0; step < FADE_STEPS; step++) {
            int base = FADE_BASE + step * 16;
            for (int digit = 0; digit <= 9; digit++) {
                map.put((int) (base + digit), digit == 1 ? 6 : 7);
            }
            map.put((int) (base + 10), 7);
            map.put((int) (base + 11), 8);
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
                        int codePoint = Integer.parseInt(pair.substring(0, colon).trim(), 16);
                        map.put(codePoint, Integer.parseInt(pair.substring(colon + 1).trim()));
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
        return width((int) ch);
    }

    private int width(int codePoint) {
        return advances.getOrDefault(codePoint, 7);
    }

    int width(String text) {
        int total = 0;
        for (int i = 0; i < text.length();) {
            int codePoint = text.codePointAt(i);
            total += width(codePoint);
            i += Character.charCount(codePoint);
        }
        return total;
    }

    /** Ширина строки шрифта панели: пробел 4, остальные — по метрикам атласа. */
    int textWidth(String text, int texty) {
        return textWidth(text, texty, false);
    }

    private int textWidth(String text, int texty, boolean small) {
        int total = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            total += ch == ' ' ? 4 : width(textGlyph(ch, texty, small));
        }
        return total;
    }

    /**
     * Ник первой строки: при достижении 11 символов переходит на компактный
     * шрифт; если даже в нём строка не помещается, аккуратно обрезается.
     */
    private int titleBudget(char diamond, String xpFraction, boolean small,
                            int contentLeft, int textx) {
        int iconGap = small ? 3 : ICON_GAP;
        int columnGap = small ? 4 : COLUMN_GAP;
        return (CONTENT_RIGHT - contentLeft - textx) - width(MONEY_SCROLL) - iconGap
                - width(diamond) - columnGap - width(xpFraction) - columnGap;
    }

    private String fitTitle(String playerName, char diamond, String xpFraction,
                            int texty, boolean small, int contentLeft, int textx) {
        int budget = titleBudget(diamond, xpFraction, small, contentLeft, textx);
        String name = playerName == null ? "" : playerName;
        while (textWidth(name, texty, small) > budget && !name.isEmpty()) {
            name = name.substring(0, name.length() - 1);
        }
        return name;
    }

    /** Индивидуальная граница текста под эмблемой и рамкой каждой панели. */
    private static int contentLeft(int classIndex) {
        return switch (classIndex) {
            case 0 -> 72; // воин: отступить от выступов щита
            case 6 -> 80; // викинг: крупный круглый щит занимает больше места
            case 7 -> 70; // фермер: оставить место корзине у нижней строки
            default -> CONTENT_LEFT;
        };
    }

    /** Опустить подложку, чтобы строки HUD поднялись над полосами в арте. */
    private static int classPanelOffset(int classIndex) {
        return switch (classIndex) {
            case 0, 6 -> -2; // воин и викинг: поднять строки на 2 px
            case 7 -> -1; // фермер: небольшая корректировка на 1 px
            default -> 0;
        };
    }

    /** Ширина глифа-панели класса: по ней считается сдвиг всей панели. */
    int panelWidth(int classIndex) {
        int index = Math.max(0, Math.min(PANEL_CLASS_COUNT - 1, classIndex));
        return width(puaCodePoint(PANEL_START_INDEX + variantIndex(0) * PANEL_CLASS_COUNT + index));
    }

    /** Глиф текста с учётом сдвига (texty) и размера шрифта. */
    private int textGlyph(char ch, int texty) {
        return textGlyph(ch, texty, false);
    }

    private int textGlyph(char ch, int texty, boolean small) {
        int index = TEXT_CHARS.indexOf(ch);
        if (index < 0) {
            index = TEXT_CHARS.indexOf('?');
        }
        int start = small ? TEXT_SMALL_START_INDEX : TEXT_START_INDEX;
        return puaCodePoint(start + variantIndex(texty) * TEXT_CHARS.length() + index);
    }

    /** Строка текста в глифы шрифта панели (пробел — space-провайдер). */
    private String toGlyphs(String text, int texty) {
        return toGlyphs(text, texty, false);
    }

    private String toGlyphs(String text, int texty, boolean small) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            // Обычный пробел некоторые версии клиента рисуют без advance в
            // bitmap-шрифте; явный space-глиф гарантирует видимый интервал.
            if (ch == ' ') {
                sb.append(BLANK_4);
            } else {
                sb.appendCodePoint(textGlyph(ch, texty, small));
            }
        }
        return sb.toString();
    }

    private static int variantIndex(int offset) {
        return Math.max(-ICON_OFFSET_RANGE, Math.min(ICON_OFFSET_RANGE, offset))
                + ICON_OFFSET_RANGE;
    }

    private static int puaCodePoint(int index) {
        return index < PUA_FIRST_PLANE_COUNT
                ? PUA_START + index
                : 0x100000 + index - PUA_FIRST_PLANE_COUNT;
    }

    private static String glyphString(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    /** Подбирает вертикальный вариант цифры/малого значка под /hud texty. */
    private static int inlineGlyph(int codePoint, int texty) {
        int offset;
        int index;
        if (codePoint >= '0' && codePoint <= '9') {
            offset = 0;
            index = codePoint - '0';
        } else if (codePoint == '/') {
            offset = 0;
            index = 10;
        } else if (codePoint >= 0xE310 && codePoint < 0xE316) {
            offset = INLINE_MONEY_OFFSET;
            index = codePoint - 0xE310;
        } else if (codePoint >= LEVEL_BASE && codePoint < LEVEL_BASE + 100) {
            offset = INLINE_LEVEL_OFFSET;
            index = codePoint - LEVEL_BASE;
        } else if (codePoint >= CLAN_BASE && codePoint < CLAN_BASE + 100) {
            offset = INLINE_CLAN_OFFSET;
            index = codePoint - CLAN_BASE;
        } else {
            return codePoint;
        }
        int variant = variantIndex(texty);
        return puaCodePoint(INLINE_START_INDEX + variant * INLINE_GLYPH_COUNT + offset + index);
    }

    private static String inlineText(String text, int texty) {
        StringBuilder shifted = new StringBuilder(text.length());
        for (int i = 0; i < text.length();) {
            int codePoint = text.codePointAt(i);
            shifted.appendCodePoint(inlineGlyph(codePoint, texty));
            i += Character.charCount(codePoint);
        }
        return shifted.toString();
    }

    /**
     * Три строки панели.
     *
     * <p>Первая - подложка-панель и поверх неё свиток, ник, ромб уровня
     * (номер нарисован на нём) и опыт дробью; эмблема слева заменяет текст
     * класса. Вторая - клан: щит уровня, название и опыт клана дробью;
     * третья - четыре валюты.</p>
     *
     * <p>Все три строки получают один и тот же сдвиг {@code P} и добиваются
     * до полной ширины панели, поэтому их центры совпадают. Начало текста
     * выбирается отдельно для класса с учётом ширины его эмблемы и декора.
     * Сдвиг панели от центра в конфиг не кладём: он в пикселях интерфейса и
     * при смене масштаба GUI меняется в разы.</p>
     *
     * @param layout геометрия под экран игрока (ширина интерфейса и отступ)
     * @param offsetY сдвиг текста и значков вниз относительно картинки
     *                панели, px (-100..100, команда {@code /hud offsety})
     * @param texty сдвиг текста вниз относительно значков, px (-100..100,
     *              команда {@code /hud texty}; цифры и малые значки следуют за текстом)
     * @param textx сдвиг всего текстового блока по горизонтали, px (-100..100,
     *              команда {@code /hud textx}; крупная эмблема остаётся на месте)
     */
    Component[] panel(HudProfile profile, String playerName, long level, long xp, long xpToNext,
                      HudLayout layout, int offsetY, int texty, int textx) {
        int panelOffset = Math.max(-PANEL_OFFSET_RANGE, Math.min(PANEL_OFFSET_RANGE,
                offsetY + classPanelOffset(profile.classIndex())));
        int variant = panelOffset + PANEL_OFFSET_RANGE;
        int panelGlyph = puaCodePoint(PANEL_START_INDEX + variant * PANEL_CLASS_COUNT
                + profile.classIndex());
        int panelWidth = width(panelGlyph);
        int contentLeft = contentLeft(profile.classIndex());
        int offset = layout.panelShift(panelWidth);

        // Строка 1: после эмблемы курсор возвращается к общей левой границе
        // текстовой полосы. Свиток, ник и уровень идут подряд; хвост сохраняет
        // одинаковую общую ширину всех трёх строк.
        String xpFraction = xp + "/" + xpToNext;
        char diamond = (char) (LEVEL_BASE + levelIndex(level));
        String playerTitle = playerName == null ? "" : playerName;
        boolean smallTitle = playerTitle.length() >= 11
                || textWidth(playerTitle, texty)
                > titleBudget(diamond, xpFraction, false, contentLeft, textx);
        int titleIconGap = smallTitle ? 3 : ICON_GAP;
        int titleColumnGap = smallTitle ? 4 : COLUMN_GAP;
        String title = fitTitle(playerTitle, diamond, xpFraction, texty,
                smallTitle, contentLeft, textx);
        int titleW = width(MONEY_SCROLL) + titleIconGap + textWidth(title, texty, smallTitle)
                + titleColumnGap + width(diamond) + titleColumnGap + width(xpFraction);
        int titleTail = panelWidth - contentLeft - textx - titleW;

        Component first = Component.text(shift(offset), GLYPHS)
                .append(Component.text(glyphString(panelGlyph), GLYPHS))
                .append(Component.text(shift(-(panelWidth - contentLeft) + textx), GLYPHS))
                .append(Component.text(inlineText(String.valueOf(MONEY_SCROLL), texty)
                        + pad(titleIconGap), GLYPHS))
                .append(Component.text(toGlyphs(title, texty, smallTitle), GLYPHS))
                .append(Component.text(pad(titleColumnGap)
                        + inlineText(String.valueOf(diamond), texty)
                        + pad(titleColumnGap) + inlineText(xpFraction, texty), GLYPHS))
                .append(Component.text(tail(titleTail), GLYPHS));

        // Строка 2: клан - щит уровня, название и опыт клана дробью.
        // Строка 2 начинается на той же координате, что и строка 1.
        char clanShield = (char) (CLAN_BASE + levelIndex(profile.clanLevel()));
        String clanXp = profile.clanXp() + "/" + profile.clanXpToNext();
        String clanName = profile.clanName().isEmpty() ? "Без клана" : profile.clanName();
        int clanBudget = (CONTENT_RIGHT - contentLeft - textx) - width(clanShield) - COLUMN_GAP
                - COLUMN_GAP - width(clanXp);
        while (textWidth(clanName, texty) > clanBudget && !clanName.isEmpty()) {
            clanName = clanName.substring(0, clanName.length() - 1);
        }
        int clanW = width(clanShield) + COLUMN_GAP + textWidth(clanName, texty)
                + COLUMN_GAP + width(clanXp);
        int clanTail = panelWidth - contentLeft - textx - clanW;
        Component second = Component.text(shift(offset) + pad(contentLeft) + shift(textx), GLYPHS)
                .append(Component.text(inlineText(String.valueOf(clanShield), texty)
                        + pad(COLUMN_GAP), GLYPHS))
                .append(Component.text(toGlyphs(clanName, texty), GLYPHS))
                .append(Component.text(pad(COLUMN_GAP) + inlineText(clanXp, texty), GLYPHS))
                .append(Component.text(tail(clanTail), GLYPHS));

        // Строка 3: четыре валюты - значок и число; начинается под первой.
        String money = "" + MONEY_COINS + pad(ICON_GAP) + profile.coins() + pad(COLUMN_GAP)
                + MONEY_STAR + pad(ICON_GAP) + profile.starCoins() + pad(COLUMN_GAP)
                + MONEY_GEM + pad(ICON_GAP) + profile.nanogems() + pad(COLUMN_GAP)
                + MONEY_BATTLE + pad(ICON_GAP) + profile.battlePoints();
        int moneyW = width(money);
        int moneyTail = panelWidth - contentLeft - textx - moneyW;
        Component third = Component.text(shift(offset) + pad(contentLeft) + shift(textx)
                + inlineText(money, texty) + tail(moneyTail), GLYPHS);

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
                     int anchorX, int abilitySlots, HudLayout layout,
                     int leftOffsetX, int leftOffsetY,
                     int rightOffsetX, int rightOffsetY) {
        int leftVariant = variantIndex(leftOffsetY);
        StringBuilder bodyBuilder = new StringBuilder();
        bodyBuilder.appendCodePoint(hotbarLeftGlyph(GEAR_HEART, leftVariant))
                .append(pad(ICON_GAP)).append(hotbarDigits(health, leftVariant))
                .append(pad(COLUMN_GAP))
                .appendCodePoint(hotbarLeftGlyph(GEAR_SWORD, leftVariant))
                .append(pad(ICON_GAP)).append(hotbarDigits(damage, leftVariant))
                .append(pad(COLUMN_GAP))
                .appendCodePoint(hotbarLeftGlyph(GEAR_SHIELD, leftVariant))
                .append(pad(ICON_GAP)).append(hotbarDigits(armor, leftVariant));
        if (fadeStep >= 0) {
            bodyBuilder.append(pad(COLUMN_GAP))
                    .append(indicator(lastDamage, fadeStep, leftVariant));
        }
        String body = bodyBuilder.toString();
        // Компенсирующие сдвиги сохраняют центр всей строки: leftX двигает
        // только статистику, а rightX — только значки способностей.
        StringBuilder line = new StringBuilder(
                shift(HudLayout.anchoredShift(anchorX, width(body), layout.interfaceWidth())));
        line.append(shift(leftOffsetX)).append(body).append(shift(-leftOffsetX));
        // Правый край хотбара - это +91 от центра; способности встают за ним.
        int slots = Math.max(0, Math.min(8, abilitySlots));
        if (slots > 0) {
            int bodyEnd = anchorX + width(body);
            line.append(pad(Math.max(0, 91 + 6 - bodyEnd)));
            line.append(shift(rightOffsetX));
            int rightVariant = variantIndex(rightOffsetY);
            for (int i = 0; i < slots; i++) {
                int glyph = puaCodePoint(HOTBAR_RIGHT_START_INDEX
                        + rightVariant * HOTBAR_RIGHT_VARIANT_GLYPH_COUNT + i);
                line.appendCodePoint(glyph);
                if (i + 1 < slots) {
                    line.append(pad(2));
                }
            }
            line.append(shift(-rightOffsetX));
        }
        return Component.text(line.toString(), GLYPHS);
    }

    /** «-25♥» глифами ступени затухания (0 - полная яркость). */
    String indicator(long amount, int fadeStep, int leftVariant) {
        int step = Math.max(0, Math.min(FADE_STEPS - 1, fadeStep));
        int start = HOTBAR_LEFT_START_INDEX + leftVariant * HOTBAR_LEFT_VARIANT_GLYPH_COUNT
                + HOTBAR_LEFT_FADE_OFFSET + step * 12;
        StringBuilder builder = new StringBuilder();
        builder.appendCodePoint(puaCodePoint(start + 10)); // минус
        String digits = Long.toString(Math.max(0L, amount));
        for (int i = 0; i < digits.length(); i++) {
            builder.appendCodePoint(puaCodePoint(start + digits.charAt(i) - '0'));
        }
        builder.appendCodePoint(puaCodePoint(start + 11)); // сердце
        return builder.toString();
    }

    private int hotbarLeftGlyph(char glyph, int variant) {
        int index = glyph == GEAR_HEART ? 0 : glyph == GEAR_SWORD ? 1 : 2;
        return puaCodePoint(HOTBAR_LEFT_START_INDEX
                + variant * HOTBAR_LEFT_VARIANT_GLYPH_COUNT
                + HOTBAR_LEFT_GEAR_OFFSET + index);
    }

    /** Число глифами цифр хотбара; его вертикальный сдвиг общий с левой группой. */
    String hotbarDigits(long value, int variant) {
        String digits = Long.toString(value);
        StringBuilder builder = new StringBuilder(digits.length());
        for (int i = 0; i < digits.length(); i++) {
            builder.appendCodePoint(puaCodePoint(HOTBAR_LEFT_START_INDEX
                    + variant * HOTBAR_LEFT_VARIANT_GLYPH_COUNT
                    + HOTBAR_LEFT_DIGIT_OFFSET + digits.charAt(i) - '0'));
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

    /** Сохраняет общую ширину строки даже если содержимое вышло за панель. */
    private String tail(int pixels) {
        return pad(Math.max(0, pixels)) + shift(Math.min(0, pixels));
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
