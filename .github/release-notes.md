Готовый серверный пак для **Paper 26.3** (Windows).

**Внутри архива**
- `server/` — `start.bat`, `settings.bat`, `update.bat`, `backup.bat`,
  `server.properties`, `eula.txt`, `QUICKSTART.txt`, служебные `.ps1`
- `server/plugins/` — четыре плагина **ServerMMO**: core, mobs, players, hud
- `server/resourcepack/servermmo-hud.zip` — рамка HUD (ставится клиенту)
- `README.md`, `PLUGINS.md`, `hud-preview.png`

**Требования:** Java 25 (Temurin), Windows.

**Первый запуск:** распаковать → двойной щёлчок по `server/start.bat`.
Ядро `paper.jar` скачается автоматически.

**Ресурспак обязателен:** скопировать `servermmo-hud.zip` в
`%APPDATA%\.minecraft\resourcepacks` и включить «ServerMMO HUD» в настройках.
Без него вместо рамки будут пустые квадраты.

**Проверено:** пак поднимается на настоящем Paper 26.3, все четыре плагина
включаются, кастомный урон снимает здоровье, смертельный удар убирает моба
без дропа и анимации смерти.
