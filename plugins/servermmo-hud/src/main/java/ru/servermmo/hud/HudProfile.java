package ru.servermmo.hud;

/**
 * Числовые данные панели игрока: класс, клан и четыре валюты.
 *
 * <p>Смысловой логики здесь нет — только числа и названия, которые оператор
 * меняет командами. Всё хранится как {@code long}, как и прочие статы.</p>
 */
final class HudProfile {

    /** Русские названия классов — ими же команда {@code /class}. */
    static final String[] CLASSES = {
            "воин", "маг", "лучник", "паладин", "фермер", "викинг", "некромант", "копейщик",
    };

    private int classIndex;
    private String clanName;
    private long clanLevel;
    private long clanXp;
    private long coins;
    private long starCoins;
    private long battlePoints;
    private long nanogems;
    /** Защита — числовая заглушка: механики брони пока нет. */
    private long armor;

    /**
     * Масштаб интерфейса игрока: что стоит у него в {@code Настройки → Видео
     * → Масштаб интерфейса}. 0 — ещё не указан, берётся общий из конфига.
     *
     * <p>Клиент его серверу не сообщает, поэтому игрок называет его сам
     * один раз — командой {@code /hud scale}. Дальше плагин считает отступ
     * панели под этот масштаб, и панель не уезжает за край экрана.</p>
     */
    private int guiScale;
    /** Ширина окна игры в реальных пикселях. 0 — взять общую из конфига. */
    private int screenWidth;
    /** Высота окна игры в реальных пикселях. 0 — взять общую из конфига. */
    private int screenHeight;

    HudProfile() {
        this.classIndex = 0;
        this.clanName = "";
        this.clanLevel = 1L;
        this.clanXp = 0L;
        this.coins = 0L;
        this.starCoins = 0L;
        this.battlePoints = 0L;
        this.nanogems = 0L;
        this.armor = 0L;
        this.guiScale = 0;
        this.screenWidth = 0;
        this.screenHeight = 0;
    }

    /** Масштаб интерфейса игрока, 0 — не задан (брать общий из конфига). */
    int guiScale() {
        return guiScale;
    }

    void guiScale(int guiScale) {
        this.guiScale = Math.max(0, guiScale);
    }

    /** Ширина окна игры в реальных пикселях, 0 — не задана. */
    int screenWidth() {
        return screenWidth;
    }

    void screenWidth(int screenWidth) {
        this.screenWidth = Math.max(0, screenWidth);
    }

    /** Высота окна игры в реальных пикселях, 0 — не задана. */
    int screenHeight() {
        return screenHeight;
    }

    void screenHeight(int screenHeight) {
        this.screenHeight = Math.max(0, screenHeight);
    }

    /** Индекс класса 0..7 — он же номер панели в ресурспаке. */
    int classIndex() {
        return Math.max(0, Math.min(CLASSES.length - 1, classIndex));
    }

    void classIndex(int classIndex) {
        this.classIndex = Math.max(0, Math.min(CLASSES.length - 1, classIndex));
    }

    /** Название класса для строки панели, с большой буквы. */
    String className() {
        String name = CLASSES[classIndex()];
        return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    String clanName() {
        return clanName == null ? "" : clanName;
    }

    void clanName(String clanName) {
        this.clanName = clanName == null ? "" : clanName;
    }

    long clanLevel() {
        return Math.max(1L, clanLevel);
    }

    void clanLevel(long clanLevel) {
        this.clanLevel = Math.max(1L, clanLevel);
    }

    long clanXp() {
        return Math.max(0L, clanXp);
    }

    void clanXp(long clanXp) {
        this.clanXp = Math.max(0L, clanXp);
    }

    long coins() {
        return coins;
    }

    void coins(long coins) {
        this.coins = coins;
    }

    long starCoins() {
        return starCoins;
    }

    void starCoins(long starCoins) {
        this.starCoins = starCoins;
    }

    long battlePoints() {
        return battlePoints;
    }

    void battlePoints(long battlePoints) {
        this.battlePoints = battlePoints;
    }

    long armor() {
        return armor;
    }

    void armor(long armor) {
        this.armor = Math.max(0L, armor);
    }

    long nanogems() {
        return nanogems;
    }

    void nanogems(long nanogems) {
        this.nanogems = nanogems;
    }
}
