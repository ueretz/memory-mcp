# memory-mcp

MCP-сервер для Claude Code, дающий агенту **долговременную память поверх PostgreSQL** вместо
плоских markdown-файлов. Память организована как на GitHub/GitLab: проект → общий ("common")
контекст продукта + отдельные папки задач, плюс отдельный тип записей — автоматически
построенный граф расположения файлов/классов в проекте, чтобы агент не грепал файловую систему.
Всё это доступно как самому агенту через MCP-инструменты, так и человеку — через локальный
веб-дашборд с навигацией в стиле GitLab и страницами записей в стиле Confluence.

## Зачем это нужно

Встроенная память Claude Code — это markdown-файлы с YAML frontmatter на диске плюс индекс
`MEMORY.md`. Это нормально работает для одного пользователя, но:
- нет структурированного поиска/фильтрации — агент вынужден читать файлы, чтобы понять, что внутри;
- нет визуализации связей между записями и нет понятия "проект → задача";
- агент не знает, где физически лежат классы/файлы, и вынужден грепать файловую систему заново
  в каждой сессии;
- нет единой точки для нескольких агентов/проектов, если понадобится расшириться.

`memory-mcp` заменяет файловое хранилище на Postgres, сохраняя категории записей
(`USER/FEEDBACK/PROJECT/REFERENCE/LOCATION`) и идею связей между ними, но даёт агенту
типизированный API, иерархию проект/задача, автоматический индекс кода и дашборд для просмотра.

## Стек технологий

| Слой | Технология | Версия | Зачем |
|---|---|---|---|
| Язык/платформа | Java | 25 | LTS, современный синтаксис (records, pattern matching) |
| Фреймворк | Spring Boot | 4.1.0 | DI, автоконфигурация, embedded Tomcat для дашборда |
| MCP-протокол | Spring AI MCP Server Starter (`spring-ai-starter-mcp-server`) | 2.0.0 (spring-ai-bom) | Аннотационный API (`@McpTool`/`@McpToolParam`) поверх `io.modelcontextprotocol.sdk`, транспорт stdio |
| БД | PostgreSQL | 17 (alpine, в Docker) | Реальный сервер с полнотекстовым поиском (`tsvector`/GIN) и запасом на рост нагрузки |
| ORM | Spring Data JPA / Hibernate | (через Spring Boot BOM) | Entities, репозитории, нативные запросы для FTS |
| Миграции схемы | Flyway (+ `flyway-database-postgresql`, + `spring-boot-flyway`) | 12.4.0 | Версионируемая схема БД (V1-V3), накатывается автоматически при старте |
| Пул соединений | HikariCP | (входит в Spring Boot) | Стандартный пул для JDBC |
| Сборка | Gradle (Groovy DSL) | wrapper в репозитории | `bootJar` собирает fat-jar `memory-mcp.jar` |
| Логирование | Logback (`logback-spring.xml`) | — | `<springProfile>`: stdio-профиль пишет в `stderr` (протокол требует чистый stdout), дашборд-профиль — обычный цветной stdout |
| Веб-слой (дашборд) | Spring MVC (`spring-boot-starter-web`) + embedded Tomcat | — | REST API для чтения данных и настройки |
| Фронтенд дашборда | Vanilla HTML/CSS/JS, ES-модули, hash-роутинг (без сборки) | — | `static/index.html` + `static/js/*`, без фреймворков и CDN-зависимостей |
| Локальный инфраструктурный слой | Docker Compose | — | Поднимает Postgres на `localhost:5433` с именованным volume |

## Как это работает

### Модель данных

Три таблицы, миграции `src/main/resources/db/migration/V1..V3`:

- **`memory_nodes`** — сама запись памяти: `name` (уникальный slug, до 500 символов — для
  `LOCATION`-записей это может быть полное имя класса), `type`
  (`USER`/`FEEDBACK`/`PROJECT`/`REFERENCE`/`LOCATION`), `description` (короткое summary для
  дешёвых списков), `content` (полный markdown), `project_scope`, `task_id` (nullable FK на
  `tasks`), `file_path` (nullable, для `LOCATION`), `search_vector` — генерируемая колонка
  `tsvector` (name/description/content с весами A/B/C) с GIN-индексом для полнотекстового поиска.
- **`memory_edges`** — связи, извлечённые из `[[other-name]]` внутри `content` (regex поддерживает
  и точки — для `[[fully.qualified.ClassName]]`). `target_id` nullable: если запись ссылается на
  ещё не существующую сущность, связь остаётся "висячей" и **самоисцеляется** при создании нужной
  записи — `MemoryService.save()` перепарсивает ссылки при каждом сохранении.
- **`tasks`** — единица работы внутри проекта: `project_scope`, `task_key` (номер тикета/задачи),
  `title`, `source` (`MANUAL`/`JIRA`), `status` (`ACTIVE`/`DONE`). Запись в `memory_nodes` с
  `task_id = NULL` — это "общий" (common) контекст проекта; с `task_id` — контекст конкретной
  задачи. Удаление задачи каскадно удаляет её записи (FK `ON DELETE CASCADE`).

Java-слой (`ru.iuribabalin.memorymcp`):
- `entity/` — `MemoryNode`, `MemoryEdge`, `Task` (JPA-сущности);
- `repository/` — `MemoryNodeRepository` (FTS с `ts_rank`, фильтрация по scope/task через
  `taskFilterMode` NONE/COMMON/TASK), `MemoryEdgeRepository`, `TaskRepository`;
- `service/LinkParser` — regex-парсер `[[name]]` (буквы/цифры/`_`/`-`/`.`) из markdown;
- `service/MemoryService` — upsert с диффом рёбер и резолвом задачи по `taskKey`, get, list,
  search, graph, related, delete;
- `service/TaskService` — start (upsert)/list/close задач;
- `service/ProjectService` — агрегация списка проектов со счётчиками;
- `service/RepositoryScanner` — обход дерева проекта и построение `LOCATION`-записей (см. ниже);
- `dto/` — `MemoryEntrySummary`/`MemoryEntryDetail` (включая `projectScope`/`taskKey`/`filePath`),
  `GraphResponse`, `TaskSummary`, `ProjectSummary`, `SetupInfo`.

### Иерархия Project → Task → Записи

`projectScope` агент всегда определяет сам (по имени git-репозитория), никогда не спрашивая
пользователя. А вот про принадлежность к задаче — **всегда спрашивает явно** (см. `SKILL.md`):
если да — резолвит номер задачи через Jira-подобный MCP-инструмент (если он есть в сессии) или
спрашивает пользователя напрямую, вызывает `task_start`, и дальше пишет рабочие заметки с
`taskKey`. Общий контекст продукта (без `taskKey`) поддерживается отдельно и живёт дольше одной
задачи.

### Автоматический граф расположения файлов/классов (`RepositoryScanner`)

Отдельная подсистема не потребовалась — `LOCATION` это обычная запись памяти с `filePath`,
доступная через те же `memory_list`/`memory_search`/`memory_graph`/`memory_get`. Инструмент
`location_scan(projectScope, rootPath)` обходит дерево проекта (исключая `.git`, `build`,
`node_modules`, `.gradle`, `.idea` и т.п.), и:
- для `*.java` файлов через regex достаёт `package`, объявление типа (`class/interface/enum/record`)
  и `import`; имя записи — полное имя класса (FQCN), а импорты, указывающие на другие
  просканированные в этом же проходе классы, становятся `[[fqcn]]`-ссылками — то есть реальными
  рёбрами графа зависимостей между классами, без какой-либо ручной работы;
- остальные файлы индексируются по относительному пути (`name = "{projectScope}:{path}"`) без
  автоматических связей — этого достаточно для "где лежит X" в любом языке/проекте.

Такой подход даёт полноценный граф зависимостей классов только для Java, для прочих языков —
просто плоский, но всё равно полезный индекс "имя → путь".

### Два режима работы одного и того же jar

Один процесс, два Spring-профиля (`src/main/resources/application.yml`, второй YAML-документ
через `spring.config.activate.on-profile: mcp-stdio`), общая JPA/Postgres-прослойка:

1. **`mcp-stdio`** — `spring.main.web-application-type=none`, `spring.ai.mcp.server.stdio=true`.
   Именно этот режим запускает Claude Code как поддочерний процесс на время сессии.
2. **default (без профиля)** — обычный embedded Tomcat на порту 8080 для дашборда — долгоживущий
   процесс, поднимаемый вручную (или через IDE run-конфигурацию).

**Критичный нюанс stdio-транспорта:** MCP по stdio требует, чтобы stdout содержал *только*
JSON-RPC кадры. `logback-spring.xml` учитывает это через `<springProfile>`: только в
`mcp-stdio`-профиле консольный appender явно таргетится на `System.err`; в дашборд-режиме логи
идут в обычный цветной stdout — так что запуск дашборда из IDE больше не выглядит "весь красным".

### MCP-инструменты

| Инструмент | Параметры | Возвращает |
|---|---|---|
| `memory_save` | `name`, `type`, `description`, `content`, `projectScope?`, `taskKey?`, `filePath?` | Upsert по имени, парсит `[[links]]` |
| `memory_get` | `name` | Полная запись + `linkedTo`/`linkedFrom` |
| `memory_list` | `type?`, `projectScope?`, `taskKey?`, `limit?`, `offset?` | Дешёвый список (без содержимого); без `taskKey` — только common-записи проекта |
| `memory_search` | `query`, `type?`, `projectScope?`, `taskKey?`, `limit?` | Полнотекстовый поиск, тот же дешёвый формат |
| `memory_graph` | `type?`, `projectScope?`, `taskKey?` | `{nodes, edges}` для визуализации |
| `memory_related` | `name`, `depth?` | Записи, связанные напрямую (сейчас только depth=1) |
| `memory_delete` | `name` | Удаление записи и её связей (каскад по FK) |
| `task_start` | `projectScope`, `taskKey`, `title?`, `source?` | Создать/возобновить задачу (upsert) |
| `task_list` | `projectScope` | Список задач проекта |
| `task_close` | `projectScope`, `taskKey` | Пометить задачу выполненной |
| `location_scan` | `projectScope`, `rootPath` | Индексирует файлы/классы проекта как `LOCATION`-записи с автоматическими рёбрами для Java |

### REST API дашборда

Без авторизации (рассчитано на localhost-only персональное использование):
`GET /api/projects`, `GET /api/projects/{scope}/tasks`, `GET /api/memory`,
`GET /api/memory/{name}`, `GET /api/memory/search?q=`, `GET /api/memory/graph`,
`GET /api/setup`, `GET /api/setup/skill`. `ApiExceptionHandler` превращает отсутствие
записи/задачи в HTTP 404 вместо 500.

### Веб-дашборд

Hash-роутинг (`static/js/router.js`) с навигацией в стиле GitLab:
- `#/` — сетка карточек проектов;
- `#/{projectScope}` — страница проекта: секции "Common" и "Tasks" (со статусом ACTIVE/DONE);
- `#/{projectScope}/{taskKey}` — страница задачи со списком её записей;
- `#/{projectScope}/{taskKey|common}/{entryName}` — страница записи в стиле Confluence:
  markdown реально рендерится (`static/js/markdown.js` — собственный безопасный рендерер:
  сначала экранирует HTML целиком, потом добавляет заголовки/bold/italic/списки/ссылки/код;
  ссылки — только `http(s)`/относительные пути; `[[wiki-links]]` резолвятся в клик по реальным
  связям записи), плюс блоки "Links to"/"Linked from";
- `#setup` — страница подключения: команда `claude mcp add` с автоматически подставленным путём
  к jar и к `java` того самого JDK, что сейчас исполняет процесс, плюс кнопка скачивания
  `SKILL.md` с инструкцией положить его в `~/.claude/skills/memory-mcp/` (user scope — чтобы
  работало во всех проектах).

Без сборки — чистый JS (ES-модули), без npm/CDN-зависимостей.

## Запуск локально

```bash
docker compose up -d                 # Postgres на localhost:5433
./gradlew bootJar                    # соберёт build/libs/memory-mcp.jar; Flyway применит миграции при старте

# Дашборд (веб-режим)
java -jar build/libs/memory-mcp.jar
# -> http://localhost:8080/  (проекты → задачи/common → записи, плюс #setup)

# MCP-сервер (то, что запускает Claude Code)
java -jar build/libs/memory-mcp.jar --spring.profiles.active=mcp-stdio
```

### Регистрация в Claude Code

Проще всего — открыть дашборд и зайти на страницу **⚙️ Setup** (`#setup`): там уже готовая,
подставленная под твою машину команда и кнопка скачивания скилла. Вручную это выглядит так:

```bash
claude mcp add --scope user memory-mcp \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/memorymcp \
  -e SPRING_DATASOURCE_USERNAME=memorymcp \
  -e SPRING_DATASOURCE_PASSWORD=memorymcp \
  -- java -jar $(pwd)/build/libs/memory-mcp.jar --spring.profiles.active=mcp-stdio

claude mcp list   # проверить, что memory-mcp подключён
```

> Важно: JDK для запуска должен быть версии 25 (Java 25 toolchain) — если системный `java`
> старше, укажите полный путь к JDK 25 в команде вместо `java` (страница `#setup` делает это
> автоматически).

Скилл `SKILL.md` (скачивается со страницы `#setup` или лежит в `src/main/resources/skill/`)
нужно положить в `~/.claude/skills/memory-mcp/SKILL.md` — он учит агента: определять
`projectScope` по git-репозиторию самостоятельно, всегда явно спрашивать про принадлежность к
задаче, вести общий контекст продукта отдельно от задач, и запускать `location_scan` вместо
grep по файловой системе.

## Структура проекта

```
src/main/java/ru/iuribabalin/memorymcp/
├── MemoryMcpApplication.java      — точка входа (@SpringBootApplication)
├── entity/                        — MemoryNode, MemoryEdge, Task
├── repository/                    — Spring Data репозитории + нативные FTS/edge-запросы
├── service/                       — LinkParser, MemoryService, TaskService, ProjectService,
│                                     RepositoryScanner, *NotFoundException
├── dto/                           — Summary/Detail/Graph/Task/Project/Setup DTO
├── mcp/                           — MemoryMcpTools, TaskMcpTools, CodeMapMcpTools (11 @McpTool)
└── ui/                            — MemoryViewController, ProjectViewController,
                                      SetupController, ApiExceptionHandler (read-only REST)

src/main/resources/
├── application.yml                — единый конфиг + профиль mcp-stdio
├── logback-spring.xml             — stdio-профиль → stderr, дашборд → обычный stdout
├── db/migration/                  — V1 (nodes/edges), V2 (tasks), V3 (LOCATION + file_path)
├── skill/SKILL.md                 — скилл для агента (также раздаётся через /api/setup/skill)
└── static/                        — index.html + css/style.css + js/ (router, views/*, markdown.js)

.claude/skills/memory-mcp/SKILL.md — зеркало skill/SKILL.md для догфудинга в этом репо
docker-compose.yml                 — локальный Postgres
```

## Статус и дальнейшие шаги

Реализовано и проверено вручную (миграции, все 11 MCP-инструментов через прямой JSON-RPC по
stdio, REST API, полный дашборд, регистрация в Claude Code, сканирование этого же репозитория
как реальный тест `location_scan`):

- [x] MCP-сервер над stdio с 11 инструментами, чистый stdout
- [x] Postgres + Flyway-схема (nodes/edges/tasks, тип LOCATION), самоисцеляющиеся dangling-связи
- [x] Иерархия Project → Task → Common/записи
- [x] Дашборд: навигация в стиле GitLab, страницы записей в стиле Confluence с рендерингом markdown
- [x] Страница `#setup` с готовой командой подключения и скачиванием SKILL.md
- [x] Автоматический граф расположения файлов/классов (`location_scan`), реальные Java-зависимости
- [x] `SKILL.md`, обучающий агента этому всему, включая always-ask про задачу

Дальше по плану (пока не запрошено, возможные следующие шаги):
- [ ] Графовая диаграмма (узлы/рёбра как визуальный граф, а не только дерево/списки) на дашборде
- [ ] Аналогичное семантическое извлечение символов для других языков (сейчас только Java)
