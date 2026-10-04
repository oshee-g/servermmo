Плагины (.jar) кладутся в эту папку. Сервер подхватывает их при запуске,
поэтому: скопировал jar -> останавливаешь сервер (команда stop) -> запускаешь снова.

После первого запуска Paper создаст рядом ещё своих файлов, в том числе
world/ (мир), logs/ (логи) и config/ (paper-global.yml, paper-world-defaults.yml).

Список плагинов, которые обычно ставят на Paper 26.3:
  EssentialsX        базовые команды: /home, /tpa, /kit, /spawn
  LuckPerms          права и группы
  CoreProtect        логи и откат гриферства
  ViaVersion/ViaBackwards / ViaRewind   заход с клиентов старых версий
  Geyser + Floodgate Bedrock/телефон/консоль (на 26.3 нужен ещё ViaVersion)
  Chunky             предгенерация мира без лагов
  Spark              profiler: искать, что вызывает лаги
  WorldEdit/WorldGuard  стройка и защита регионов
  Simple Voice Chat  голосовой чат

Скачивать лучше с первоисточников:
  https://modrinth.com/plugins
  https://www.spigotmc.org/resources/
  https://hangar.papermc.io/

Перед установкой проверь, что плагин заявляет поддержку 26.3: у многих
после смены версии игры поддержка появляется с задержкой.
