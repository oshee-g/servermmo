package ru.servermmo.hud;

/**
 * Геометрия панели под конкретный экран: сколько пикселей сдвигать строку.
 *
 * <p>Строку босс-бара клиент центрует сам, и от этого вся математика.
 * Пусть {@code W} — ширина интерфейса в пикселях GUI (экран поделён на
 * масштаб), {@code P} — ведущий сдвиг строки (суммарная ширина глифов
 * сдвига, она же может быть отрицательной), {@code C} — ширина самого
 * содержимого. Тогда строка занимает {@code P + C} пикселей, её левый край
 * встаёт в {@code W/2 - (P + C)/2}, а содержимое начинается на {@code P}
 * правее — то есть левый край содержимого</p>
 *
 * <pre>
 *     L = W/2 + (P - C)/2
 * </pre>
 *
 * <p>Два следствия, из-за которых без расчёта не обойтись:</p>
 * <ol>
 *   <li>сдвиг {@code P} двигает содержимое ровно на <b>половину</b> своей
 *       величины: центр строки всегда в {@code W/2 + P/2}, какой бы ни была
 *       ширина содержимого. Поэтому «подобрать отступ на глаз» можно только
 *       под один конкретный масштаб;</li>
 *   <li>ширина интерфейса {@code W} зависит и от разрешения окна, и от
 *       масштаба GUI, а сервер их не знает (клиент не присылает ни то, ни
 *       другое — см. {@code ClientInformation}).</li>
 * </ol>
 *
 * <p>Отсюда рабочая формула: чтобы левый край содержимого шириной {@code C}
 * стоял ровно в {@code left} пикселях от левого края экрана, нужен сдвиг</p>
 *
 * <pre>
 *     P = 2 * left - W + C
 * </pre>
 *
 * <p>Значит, чтобы панель не уезжала за экран при смене масштаба, надо
 * хранить не сам отступ (он в пикселях интерфейса и меняется вместе с
 * масштабом), а <b>отступ от края экрана</b> {@code margin-left}: он от
 * масштаба не зависит. Пересчёт занимает одна команда —
 * {@code /hud scale <масштаб>}.</p>
 */
final class HudLayout {

    /**
     * Предел сдвига глифами пака: биты 0..11 дают ±4095 px.
     *
     * <p>Раньше хватало 10 бит (±1023 px) — ровно настолько, чтобы дотянуть
     * панель до левого края на 1920 px при масштабе 4. На масштабе 1 и на
     * широком экране интерфейс бывает шире 2048 px, и панель упиралась в
     * предел, оставаясь посередине экрана.</p>
     */
    static final int MAX_SHIFT = (1 << 12) - 1;

    /** Ширина интерфейса в пикселях GUI: ширина окна, делённая на масштаб. */
    private final int interfaceWidth;
    /** Отступ левого края панели от левого края экрана, в пикселях GUI. */
    private final int marginLeft;

    HudLayout(int interfaceWidth, int marginLeft) {
        this.interfaceWidth = Math.max(1, interfaceWidth);
        this.marginLeft = marginLeft;
    }

    int interfaceWidth() {
        return interfaceWidth;
    }

    int marginLeft() {
        return marginLeft;
    }

    /** Ведущий сдвиг строки, левый край которой встанет ровно в {@code left}. */
    static int shiftTo(int left, int contentWidth, int interfaceWidth) {
        return clamp(2 * left - interfaceWidth + contentWidth);
    }

    /** Сдвиг строки панели: её левый край встанет в {@code margin-left}. */
    int panelShift(int panelWidth) {
        return shiftTo(marginLeft, panelWidth, interfaceWidth);
    }

    /**
     * Сдвиг строки хотбара.
     *
     * <p>Хотбар — элемент фиксированной ширины (182 px) и always стоит по
     * центру, поэтому его левый край — это {@code W/2 + anchorX} при любом
     * масштабе: сам якорь от масштаба не зависит и пересчитывать его не надо.
     * Важно удвоить его: сдвиг двигает строку лишь наполовину своей величины.</p>
     */
    static int anchoredShift(int anchorX, int contentWidth, int interfaceWidth) {
        return shiftTo(interfaceWidth / 2 + anchorX, contentWidth, interfaceWidth);
    }

    private static int clamp(int shift) {
        return Math.max(-MAX_SHIFT, Math.min(MAX_SHIFT, shift));
    }
}
