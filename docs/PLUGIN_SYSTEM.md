# Плагинная система (Plugin Architecture)

Плагинная система позволяет выносить компоненты GolemCore Bot (каналы, LLM-провайдеры, инструменты, голосовые адаптеры и т.д.) в отдельные JAR-файлы, которые загружаются в runtime из директории `plugins/`. Плагины разрабатываются, версионируются и публикуются независимо от ядра — в отдельном репозитории.

---

## Оглавление

- [Мотивация](#мотивация)
- [Общая архитектура](#общая-архитектура)
- [Структура репозиториев](#структура-репозиториев)
- [Модульная структура Maven](#модульная-структура-maven)
- [Plugin SDK (golemcore-api)](#plugin-sdk-golemcore-api)
- [Фреймворк PF4J](#фреймворк-pf4j)
- [Жизненный цикл плагина](#жизненный-цикл-плагина)
- [Манифест плагина](#манифест-плагина)
- [Типы плагинов и Extension Points](#типы-плагинов-и-extension-points)
- [Примеры плагинов](#примеры-плагинов)
- [Управление плагинами в ядре (PluginManager)](#управление-плагинами-в-ядре-pluginmanager)
- [Маркетплейс](#маркетплейс)
- [REST API управления плагинами](#rest-api-управления-плагинами)
- [Slash-команда /plugins](#slash-команда-plugins)
- [Dashboard UI](#dashboard-ui)
- [Конфигурация плагинов](#конфигурация-плагинов)
- [Изоляция и ClassLoader](#изоляция-и-classloader)
- [Зависимости между плагинами](#зависимости-между-плагинами)
- [Версионирование и совместимость](#версионирование-и-совместимость)
- [Безопасность](#безопасность)
- [Миграция существующих компонентов](#миграция-существующих-компонентов)
- [Docker-деплой с плагинами](#docker-деплой-с-плагинами)
- [Интеграция с механизмом автообновления](#интеграция-с-механизмом-автообновления)
- [План внедрения](#план-внедрения)
- [Список файлов](#список-файлов)

---

## Мотивация

### Текущие проблемы

1. **Монолитная сборка.** Все компоненты (Telegram, ElevenLabs, Brave Search, Playwright, IMAP/SMTP, LightRAG) живут в одном JAR. Добавление нового канала или инструмента требует изменения основного репозитория.

2. **Тяжёлый образ.** Docker-образ включает зависимости всех компонентов, даже если пользователь использует только Telegram + OpenAI. Base image уже содержит Chromium, Node.js, Python — всё это нужно лишь части пользователей.

3. **Связанные релизы.** Исправление бага в Telegram-адаптере требует пересборки и обновления всего приложения.

4. **Порог входа для контрибьюторов.** Чтобы добавить новый инструмент, нужно разобраться во всём проекте, настроить его полностью, пройти все проверки (PMD, SpotBugs) для всего кода.

### Что даёт плагинная система

- **Независимая разработка.** Каждый плагин — отдельный проект со своим релизным циклом.
- **Лёгкое ядро.** Минимальный образ содержит только core + API. Пользователь добавляет только нужные плагины.
- **Маркетплейс.** Пользователи устанавливают плагины из каталога через дашборд или команду.
- **Расширяемость.** Сторонние разработчики могут создавать свои плагины без форка основного проекта.

---

## Общая архитектура

```
┌────────────────────────────────────────────────────────────────────┐
│  Docker Container                                                  │
│                                                                    │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  GolemCore Core (golemcore-app.jar)                          │  │
│  │                                                              │  │
│  │  ┌─────────────┐  ┌──────────────┐  ┌───────────────────┐   │  │
│  │  │ AgentLoop   │  │ PluginManager│  │ REST API          │   │  │
│  │  │ (pipeline)  │  │ (PF4J)       │  │ /api/plugins/*    │   │  │
│  │  └──────┬──────┘  └──────┬───────┘  └───────────────────┘   │  │
│  │         │                │                                   │  │
│  │         │   ┌────────────┴────────────────┐                  │  │
│  │         │   │  Extension Point Registry   │                  │  │
│  │         │   │                              │                  │  │
│  │         │   │  ToolComponent[]             │                  │  │
│  │         │   │  ChannelPort[]               │                  │  │
│  │         │   │  LlmProviderAdapter[]        │                  │  │
│  │         │   │  VoicePort[]                 │                  │  │
│  │         │   │  BrowserPort[]               │                  │  │
│  │         │   │  AgentSystem[]               │                  │  │
│  │         │   └─────────────────────────────┘                  │  │
│  │         │                │                                   │  │
│  └─────────┼────────────────┼───────────────────────────────────┘  │
│            │                │                                      │
│  ┌─────────┴────────────────┴───────────────────────────────────┐  │
│  │  /data/plugins/  (Docker volume)                             │  │
│  │                                                              │  │
│  │  ┌──────────────┐ ┌──────────────┐ ┌──────────────────────┐ │  │
│  │  │ telegram-    │ │ elevenlabs-  │ │ brave-search-        │ │  │
│  │  │ plugin-1.2.0 │ │ plugin-1.0.0 │ │ plugin-1.1.0         │ │  │
│  │  │ .jar         │ │ .jar         │ │ .jar                 │ │  │
│  │  │              │ │              │ │                      │ │  │
│  │  │ ChannelPort  │ │ VoicePort    │ │ ToolComponent        │ │  │
│  │  │ (telegram)   │ │ (elevenlabs) │ │ (brave_search)       │ │  │
│  │  └──────────────┘ └──────────────┘ └──────────────────────┘ │  │
│  └──────────────────────────────────────────────────────────────┘  │
│                                                                    │
│  golemcore-api.jar  ← SDK: интерфейсы и модели (в classpath ядра │
│                       И каждого плагина)                           │
└────────────────────────────────────────────────────────────────────┘
```

### Поток данных

```
Пользователь (Telegram)
       │
       ▼
┌─────────────────────┐     ┌──────────────────┐
│ TelegramPlugin      │────▶│ AgentLoop        │
│ (ChannelPort)       │     │ (Core)           │
│ plugins/telegram-.. │     │                  │
└─────────────────────┘     │  ContextBuilding │
                            │       │          │
                            │       ▼          │
                            │  ToolLoop ──────────▶ BraveSearchPlugin (ToolComponent)
                            │       │          │    plugins/brave-search-...
                            │       ▼          │
                            │  LlmAdapterFactory ─▶ Core: Langchain4jAdapter
                            │       │          │    (или LlmPlugin из plugins/)
                            │       ▼          │
                            │  ResponseRouting │
                            └──────┬───────────┘
                                   │
                                   ▼
                            TelegramPlugin.sendMessage()
```

---

## Структура репозиториев

Ключевое решение: **плагины живут в отдельном репозитории**. Основной репозиторий содержит ядро и SDK.

### Репозиторий 1: `golemcore-bot` (основной, текущий)

```
golemcore-bot/
├── golemcore-api/            ← SDK для плагинов (публикуется в Maven)
│   ├── pom.xml
│   └── src/main/java/
│       └── me/golemcore/bot/
│           ├── api/                  ← Extension Point аннотации
│           │   └── ExtensionPoint.java
│           ├── domain/
│           │   ├── component/        ← Component, ToolComponent, LlmComponent...
│           │   └── model/            ← ToolDefinition, ToolResult, Message,
│           │                            LlmRequest, LlmResponse, Skill, etc.
│           └── port/
│               ├── inbound/          ← ChannelPort, CommandPort
│               └── outbound/         ← LlmPort, VoicePort, BrowserPort, RagPort...
│
├── golemcore-core/           ← Ядро (AgentLoop, Systems, Services)
│   ├── pom.xml
│   └── src/main/java/
│       └── me/golemcore/bot/
│           ├── domain/
│           │   ├── loop/             ← AgentLoop, AgentContextHolder
│           │   ├── service/          ← SessionService, SkillService, UpdateService...
│           │   └── system/           ← Все pipeline-системы
│           ├── infrastructure/
│           │   ├── config/           ← BotProperties, SecurityConfig...
│           │   └── plugin/           ← GolemPluginManager, PluginBridge
│           ├── security/             ← InjectionGuard, AllowlistValidator
│           └── tools/                ← Встроенные инструменты (DateTime, PlanGet...)
│
├── golemcore-app/            ← Spring Boot application (собирает всё вместе)
│   ├── pom.xml
│   └── src/main/java/
│       └── me/golemcore/bot/
│           └── BotApplication.java
│
├── pom.xml                   ← Parent POM (multi-module)
├── misc/
│   ├── docker/
│   │   └── app/entrypoint.sh
│   └── Dockerfile.base
└── docs/
```

### Репозиторий 2: `golemcore-plugins` (новый, отдельный)

```
golemcore-plugins/
├── pom.xml                           ← Parent POM для всех плагинов
│
├── telegram-plugin/
│   ├── pom.xml                       ← зависит от golemcore-api
│   └── src/main/java/
│       └── me/golemcore/plugin/telegram/
│           ├── TelegramPlugin.java           ← Plugin class (PF4J lifecycle)
│           ├── TelegramChannelAdapter.java   ← @Extension ChannelPort
│           ├── TelegramVoiceHandler.java
│           └── TelegramMenuHandler.java
│
├── elevenlabs-plugin/
│   ├── pom.xml
│   └── src/main/java/
│       └── me/golemcore/plugin/elevenlabs/
│           ├── ElevenLabsPlugin.java
│           └── ElevenLabsVoiceAdapter.java   ← @Extension VoicePort
│
├── brave-search-plugin/
│   ├── pom.xml
│   └── src/main/java/
│       └── me/golemcore/plugin/brave/
│           ├── BraveSearchPlugin.java
│           └── BraveSearchTool.java          ← @Extension ToolComponent
│
├── playwright-plugin/
│   ├── pom.xml
│   └── src/main/java/
│       └── me/golemcore/plugin/playwright/
│           ├── PlaywrightPlugin.java
│           └── PlaywrightBrowserAdapter.java ← @Extension BrowserPort
│
├── email-plugin/
│   ├── pom.xml
│   └── src/main/java/
│       └── me/golemcore/plugin/email/
│           ├── EmailPlugin.java
│           ├── ImapTool.java                 ← @Extension ToolComponent
│           └── SmtpTool.java                 ← @Extension ToolComponent
│
├── lightrag-plugin/
│   ├── pom.xml
│   └── src/main/java/
│       └── me/golemcore/plugin/lightrag/
│           ├── LightRagPlugin.java
│           └── LightRagAdapter.java          ← @Extension RagPort
│
├── weather-plugin/
│   ├── pom.xml
│   └── src/main/java/
│       └── me/golemcore/plugin/weather/
│           ├── WeatherPlugin.java
│           └── WeatherTool.java              ← @Extension ToolComponent
│
└── .github/
    └── workflows/
        └── build-plugins.yml                 ← CI: сборка + публикация
```

### Почему два репозитория

| Аспект | Один репо (монорепо) | Два репо |
|--------|---------------------|----------|
| **Релизный цикл** | Связан: релиз ядра = релиз всех плагинов | Независим: плагины обновляются отдельно |
| **CI/CD** | Один пайплайн, долгая сборка | Отдельные пайплайны, быстрые сборки |
| **Порог входа** | Нужно клонировать всё | Можно клонировать только нужный плагин |
| **Версионирование** | Сложнее: один version для всех | Каждый плагин — свой semver |
| **Dependency management** | Прямые ссылки между модулями | golemcore-api публикуется в Maven, плагины зависят от него |
| **Сторонние плагины** | Только через fork | Свой репо, зависимость на golemcore-api из Maven Central / GitHub Packages |

**Выбор: два репозитория**, потому что:
1. Плагины должны иметь независимый релизный цикл
2. Сторонние разработчики должны иметь возможность создавать плагины без форка
3. `golemcore-api` публикуется как библиотека — это стандартный контракт

---

## Модульная структура Maven

### Parent POM (golemcore-bot)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project>
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.0.2</version>
    </parent>

    <groupId>me.golemcore</groupId>
    <artifactId>golemcore-bot-parent</artifactId>
    <version>0.2.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <modules>
        <module>golemcore-api</module>
        <module>golemcore-core</module>
        <module>golemcore-app</module>
    </modules>

    <properties>
        <java.version>17</java.version>
        <pf4j.version>3.12.0</pf4j.version>
        <pf4j-spring.version>0.9.0</pf4j-spring.version>
    </properties>

    <!-- Общие зависимости и плагины -->
</project>
```

### golemcore-api (SDK)

```xml
<project>
    <parent>
        <groupId>me.golemcore</groupId>
        <artifactId>golemcore-bot-parent</artifactId>
        <version>0.2.0-SNAPSHOT</version>
    </parent>

    <artifactId>golemcore-api</artifactId>
    <description>GolemCore Bot Plugin SDK — интерфейсы и модели для разработки плагинов</description>

    <dependencies>
        <!-- Минимальные зависимости: только то, что нужно для интерфейсов -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-annotations</artifactId>
        </dependency>
        <dependency>
            <groupId>org.pf4j</groupId>
            <artifactId>pf4j</artifactId>
            <version>${pf4j.version}</version>
        </dependency>
        <!-- Reactor для Flux в LlmPort.chatStream() -->
        <dependency>
            <groupId>io.projectreactor</groupId>
            <artifactId>reactor-core</artifactId>
        </dependency>
    </dependencies>
</project>
```

**Критически важно:** `golemcore-api` содержит **только** интерфейсы и модели. Никакой бизнес-логики, никакого Spring-контекста, никаких адаптеров. Это то, от чего зависят все плагины.

### golemcore-core

```xml
<project>
    <parent>
        <groupId>me.golemcore</groupId>
        <artifactId>golemcore-bot-parent</artifactId>
        <version>0.2.0-SNAPSHOT</version>
    </parent>

    <artifactId>golemcore-core</artifactId>

    <dependencies>
        <dependency>
            <groupId>me.golemcore</groupId>
            <artifactId>golemcore-api</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.pf4j</groupId>
            <artifactId>pf4j-spring</artifactId>
            <version>${pf4j-spring.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>
        <!-- Все остальные зависимости ядра -->
    </dependencies>
</project>
```

### golemcore-app

```xml
<project>
    <parent>
        <groupId>me.golemcore</groupId>
        <artifactId>golemcore-bot-parent</artifactId>
        <version>0.2.0-SNAPSHOT</version>
    </parent>

    <artifactId>golemcore-app</artifactId>

    <dependencies>
        <dependency>
            <groupId>me.golemcore</groupId>
            <artifactId>golemcore-core</artifactId>
            <version>${project.version}</version>
        </dependency>
        <!-- Встроенные плагины (опционально, для обратной совместимости) -->
    </dependencies>

    <!-- Jib, spring-boot-maven-plugin и пр. -->
</project>
```

### POM плагина (в golemcore-plugins)

```xml
<project>
    <parent>
        <groupId>me.golemcore</groupId>
        <artifactId>golemcore-plugins-parent</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>golemcore-telegram-plugin</artifactId>
    <version>1.2.0</version>

    <properties>
        <golemcore-api.version>0.2.0</golemcore-api.version>
        <plugin.id>golemcore-telegram</plugin.id>
        <plugin.class>me.golemcore.plugin.telegram.TelegramPlugin</plugin.class>
        <plugin.version>${project.version}</plugin.version>
        <plugin.requires>>=0.2.0</plugin.requires>
        <plugin.provider>GolemCore Team</plugin.provider>
        <plugin.description>Telegram channel adapter with voice, menus and inline keyboards</plugin.description>
    </properties>

    <dependencies>
        <!-- SDK — provided, не включается в JAR плагина -->
        <dependency>
            <groupId>me.golemcore</groupId>
            <artifactId>golemcore-api</artifactId>
            <version>${golemcore-api.version}</version>
            <scope>provided</scope>
        </dependency>

        <!-- Зависимости самого плагина — включаются в JAR -->
        <dependency>
            <groupId>com.github.pengrad</groupId>
            <artifactId>java-telegram-bot-api</artifactId>
            <version>7.4.0</version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <!-- PF4J: генерирует MANIFEST.MF с plugin metadata -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-jar-plugin</artifactId>
                <configuration>
                    <archive>
                        <manifestEntries>
                            <Plugin-Id>${plugin.id}</Plugin-Id>
                            <Plugin-Class>${plugin.class}</Plugin-Class>
                            <Plugin-Version>${plugin.version}</Plugin-Version>
                            <Plugin-Requires>${plugin.requires}</Plugin-Requires>
                            <Plugin-Provider>${plugin.provider}</Plugin-Provider>
                            <Plugin-Description>${plugin.description}</Plugin-Description>
                        </manifestEntries>
                    </archive>
                </configuration>
            </plugin>

            <!-- Shade: упаковать зависимости плагина в один JAR -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals><goal>shade</goal></goals>
                        <configuration>
                            <createDependencyReducedPom>false</createDependencyReducedPom>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

---

## Plugin SDK (golemcore-api)

### Что входит в SDK

SDK — это модуль `golemcore-api`, от которого зависит каждый плагин (`scope=provided`). Он содержит контракт между ядром и плагинами.

```
golemcore-api/src/main/java/me/golemcore/bot/
│
├── api/
│   ├── ExtensionPoint.java           ← Маркерная аннотация PF4J
│   └── PluginContext.java            ← Контекст, передаваемый плагинам при инициализации
│
├── domain/
│   ├── component/
│   │   ├── Component.java            ← Базовый интерфейс
│   │   ├── ToolComponent.java        ← Extension Point: инструменты
│   │   ├── LlmComponent.java         ← Extension Point: LLM-провайдеры
│   │   ├── BrowserComponent.java     ← Extension Point: браузер
│   │   ├── SkillComponent.java       ← Extension Point: навыки
│   │   ├── MemoryComponent.java      ← Extension Point: память
│   │   └── SanitizerComponent.java   ← Extension Point: санитизация
│   │
│   └── model/
│       ├── ToolDefinition.java       ← Определение инструмента (JSON Schema)
│       ├── ToolResult.java           ← Результат выполнения инструмента
│       ├── ToolFailureKind.java      ← Тип ошибки инструмента
│       ├── Message.java              ← Сообщение (включая ToolCall)
│       ├── LlmRequest.java           ← Запрос к LLM
│       ├── LlmResponse.java          ← Ответ LLM
│       ├── LlmChunk.java             ← Chunk стриминга
│       ├── LlmUsage.java             ← Статистика использования
│       ├── Skill.java                ← Модель навыка
│       ├── AudioFormat.java          ← Форматы аудио
│       └── AgentSession.java         ← Сессия (read-only view)
│
└── port/
    ├── inbound/
    │   ├── ChannelPort.java          ← Extension Point: входные каналы
    │   └── CommandPort.java          ← Extension Point: команды
    │
    └── outbound/
        ├── LlmPort.java             ← Extension Point: LLM-провайдеры
        ├── LlmProviderAdapter.java  ← Extension Point: адаптеры LLM
        ├── VoicePort.java           ← Extension Point: голос (STT/TTS)
        ├── BrowserPort.java         ← Extension Point: браузер
        ├── RagPort.java             ← Extension Point: RAG
        ├── StoragePort.java         ← Порт хранилища (read-only для плагинов)
        └── McpPort.java             ← MCP клиент
```

### PluginContext

Плагины не имеют прямого доступа к Spring-контексту ядра. Вместо этого они получают `PluginContext` — ограниченный набор сервисов:

```java
/**
 * Контекст, предоставляемый плагинам при инициализации.
 * Ограничивает доступ плагинов к внутренностям ядра.
 */
public interface PluginContext {

    /**
     * Хранилище — для чтения/записи данных плагина.
     * Плагин работает в изолированной директории: plugins/{pluginId}/
     */
    StoragePort getStorage();

    /**
     * Конфигурация плагина (из runtime-config или переменных окружения).
     */
    Map<String, String> getConfiguration();

    /**
     * Отправка событий в ядро (уведомления, ошибки).
     */
    void publishEvent(Object event);

    /**
     * Текущая версия ядра.
     */
    String getCoreVersion();

    /**
     * Логгер для плагина.
     */
    org.slf4j.Logger getLogger(Class<?> clazz);
}
```

### Принцип минимальных зависимостей

`golemcore-api` намеренно содержит минимум зависимостей:

| Зависимость | Зачем |
|-------------|-------|
| `lombok` (provided) | `@Data`, `@Builder` на моделях |
| `jackson-annotations` | `@JsonIgnore` и подобные на моделях |
| `pf4j` | `@ExtensionPoint`, `@Extension` аннотации |
| `reactor-core` | `Flux` в `LlmPort.chatStream()` |

Spring Boot **не** входит в SDK. Плагины не обязаны использовать Spring внутри (хотя могут, через `pf4j-spring`).

---

## Фреймворк PF4J

### Почему PF4J

[PF4J (Plugin Framework for Java)](https://github.com/pf4j/pf4j) — легковесный фреймворк для плагинов, используемый в SonarQube, Gravitee, Flowable и других Java-продуктах.

| Критерий | PF4J | Java SPI (ServiceLoader) | OSGi |
|----------|------|--------------------------|------|
| Вес | ~100 KB | 0 (встроен) | ~5 MB |
| ClassLoader изоляция | Да | Нет | Да |
| Версионирование | Да (semver) | Нет | Да |
| Зависимости между плагинами | Да | Нет | Да |
| Hot-reload | Да (load/unload/reload) | Нет | Да |
| Spring интеграция | `pf4j-spring` | Вручную | Spring DM |
| Сложность | Низкая | Минимальная | Высокая |
| Манифест | MANIFEST.MF | META-INF/services | OSGi headers |

PF4J — оптимальный баланс между простотой SPI и мощностью OSGi.

### Ключевые концепции PF4J

```
Plugin           — жизненный цикл: create → start → stop → delete
ExtensionPoint   — маркерный интерфейс, точка расширения (наши Port-ы и Component-ы)
Extension        — реализация ExtensionPoint в плагине
PluginManager    — управляет загрузкой, запуском, остановкой плагинов
PluginClassLoader— изоляция: каждый плагин в своём ClassLoader
PluginDescriptor — метаданные из MANIFEST.MF (id, version, requires, dependencies)
```

### Интеграция с Spring

`pf4j-spring` предоставляет `SpringPluginManager`, который:
1. Загружает плагины из директории
2. Регистрирует `@Extension`-классы как Spring-бины в контексте приложения
3. Позволяет инжектировать сервисы ядра в extension-ы
4. Поддерживает lifecycle-хуки

---

## Жизненный цикл плагина

```
                    PluginManager.loadPlugins()
                           │
                           ▼
┌──────────┐       ┌──────────────┐       ┌──────────┐
│ CREATED  │──────▶│   RESOLVED   │──────▶│ STARTED  │
│          │       │ (зависимости │       │          │
│          │       │  проверены)  │       │          │
└──────────┘       └──────────────┘       └─────┬────┘
                                                │
                           ┌────────────────────┘
                           │
                           ▼
                   ┌──────────────┐       ┌──────────┐
                   │   STOPPED    │──────▶│ DISABLED │
                   │              │       │          │
                   └──────────────┘       └──────────┘
```

### Plugin класс

Каждый плагин имеет главный класс, наследующий `Plugin` из PF4J:

```java
package me.golemcore.plugin.telegram;

import org.pf4j.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Telegram channel plugin for GolemCore Bot.
 */
public class TelegramPlugin extends Plugin {

    private static final Logger LOG = LoggerFactory.getLogger(TelegramPlugin.class);

    @Override
    public void start() {
        LOG.info("Telegram plugin started");
    }

    @Override
    public void stop() {
        LOG.info("Telegram plugin stopped");
    }

    @Override
    public void delete() {
        LOG.info("Telegram plugin deleted — cleaning up resources");
    }
}
```

### Extension (реализация Extension Point)

```java
package me.golemcore.plugin.telegram;

import me.golemcore.bot.port.inbound.ChannelPort;
import org.pf4j.Extension;

/**
 * Telegram adapter, реализующий ChannelPort.
 *
 * Аннотация @Extension делает этот класс видимым для PF4J.
 * PluginManager обнаруживает его и регистрирует в ExtensionPoint Registry.
 */
@Extension
public class TelegramChannelAdapter implements ChannelPort {

    @Override
    public String getChannelType() {
        return "telegram";
    }

    @Override
    public void start() {
        // Инициализация Telegram Bot API long-polling
    }

    // ... остальные методы ChannelPort
}
```

---

## Манифест плагина

Каждый плагин-JAR содержит `META-INF/MANIFEST.MF` с метаданными PF4J:

```
Manifest-Version: 1.0
Plugin-Id: golemcore-telegram
Plugin-Class: me.golemcore.plugin.telegram.TelegramPlugin
Plugin-Version: 1.2.0
Plugin-Requires: >=0.2.0
Plugin-Provider: GolemCore Team
Plugin-Description: Telegram channel adapter with voice, menus and inline keyboards
Plugin-Dependencies:
Plugin-License: Apache-2.0
```

### Поля манифеста

| Поле | Обязательно | Описание |
|------|-------------|----------|
| `Plugin-Id` | Да | Уникальный идентификатор плагина |
| `Plugin-Class` | Да | Полное имя класса, наследующего `Plugin` |
| `Plugin-Version` | Да | Версия плагина (semver) |
| `Plugin-Requires` | Да | Требуемая версия ядра (semver range: `>=0.2.0`, `>=0.2.0 & <1.0.0`) |
| `Plugin-Provider` | Нет | Автор/организация |
| `Plugin-Description` | Нет | Описание для маркетплейса |
| `Plugin-Dependencies` | Нет | Зависимости от других плагинов: `plugin1@>=1.0.0, plugin2` |
| `Plugin-License` | Нет | SPDX-идентификатор лицензии |

---

## Типы плагинов и Extension Points

### Какие Extension Points доступны

Каждый существующий порт-интерфейс и Component-интерфейс становится Extension Point:

| Extension Point | Тип | Описание | Пример плагина |
|-----------------|-----|----------|----------------|
| `ChannelPort` | Inbound | Канал ввода/вывода (Telegram, Discord, Slack...) | telegram-plugin, discord-plugin |
| `ToolComponent` | Tool | Инструмент, вызываемый LLM | brave-search-plugin, email-plugin, weather-plugin |
| `LlmProviderAdapter` | LLM | Провайдер LLM (OpenAI, Anthropic, Ollama...) | ollama-plugin, gemini-plugin |
| `VoicePort` | Voice | Сервис STT/TTS | elevenlabs-plugin, whisper-plugin |
| `BrowserPort` | Browser | Headless-браузер | playwright-plugin, puppeteer-plugin |
| `RagPort` | RAG | Поисковый движок (RAG) | lightrag-plugin, pgvector-plugin |
| `AgentSystem` | Pipeline | Система в пайплайне агентского цикла | analytics-plugin, moderation-plugin |
| `CommandPort` | Command | Обработчик slash-команд | custom-commands-plugin |

### Категория плагинов

Для маркетплейса и UI плагины группируются по категориям:

```java
public enum PluginCategory {
    CHANNEL,        // Каналы (Telegram, Discord, Slack, WhatsApp)
    TOOL,           // Инструменты (Brave Search, Email, Weather, Calendar)
    LLM_PROVIDER,   // LLM-провайдеры (Ollama, Gemini, Mistral)
    VOICE,          // Голос (ElevenLabs, Whisper, Google TTS)
    BROWSER,        // Браузер (Playwright, Puppeteer)
    RAG,            // RAG (LightRAG, pgvector, Qdrant)
    PIPELINE,       // Pipeline-системы (модерация, аналитика, логирование)
    OTHER           // Прочее
}
```

---

## Примеры плагинов

### Пример 1: Простой инструмент (BraveSearchTool)

Самый простой тип плагина — один `ToolComponent`:

```java
// BraveSearchPlugin.java
package me.golemcore.plugin.brave;

import org.pf4j.Plugin;

public class BraveSearchPlugin extends Plugin {
    // Lifecycle hooks если нужно, иначе пустой
}
```

```java
// BraveSearchTool.java
package me.golemcore.plugin.brave;

import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.model.ToolDefinition;
import me.golemcore.bot.domain.model.ToolResult;
import org.pf4j.Extension;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Extension
public class BraveSearchTool implements ToolComponent {

    private static final String API_BASE = "https://api.search.brave.com/res/v1/web/search";

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.builder()
                .name("brave_search")
                .description("Search the web using Brave Search API")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string", "description", "Search query"),
                                "count", Map.of("type", "integer", "description", "Number of results", "default", 5)
                        ),
                        "required", java.util.List.of("query")
                ))
                .build();
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        String query = (String) parameters.get("query");
        int count = parameters.getOrDefault("count", 5) instanceof Number n ? n.intValue() : 5;

        String apiKey = System.getenv("BRAVE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            return CompletableFuture.completedFuture(
                    ToolResult.failure("BRAVE_API_KEY not configured"));
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + "?q=" + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8) + "&count=" + count))
                .header("Accept", "application/json")
                .header("X-Subscription-Token", apiKey)
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        return ToolResult.success(response.body());
                    }
                    return ToolResult.failure("Brave Search API error: " + response.statusCode());
                })
                .exceptionally(e -> ToolResult.failure("Search failed: " + e.getMessage()));
    }

    @Override
    public boolean isEnabled() {
        String apiKey = System.getenv("BRAVE_API_KEY");
        return apiKey != null && !apiKey.isBlank();
    }
}
```

Это полностью самодостаточный плагин: один JAR, никаких зависимостей кроме `golemcore-api`.

### Пример 2: Канал (Telegram)

Более сложный плагин, содержащий несколько взаимосвязанных классов:

```java
// TelegramPlugin.java
package me.golemcore.plugin.telegram;

import org.pf4j.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TelegramPlugin extends Plugin {

    private static final Logger LOG = LoggerFactory.getLogger(TelegramPlugin.class);

    @Override
    public void start() {
        LOG.info("[Telegram] Plugin started");
    }

    @Override
    public void stop() {
        LOG.info("[Telegram] Plugin stopped");
    }
}
```

```java
// TelegramChannelAdapter.java
package me.golemcore.plugin.telegram;

import me.golemcore.bot.domain.model.Message;
import me.golemcore.bot.port.inbound.ChannelPort;
import org.pf4j.Extension;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Extension
public class TelegramChannelAdapter implements ChannelPort {

    private final List<Consumer<Message>> messageHandlers = new CopyOnWriteArrayList<>();
    private volatile boolean running = false;

    // Telegram Bot API client инициализируется из конфигурации

    @Override
    public String getChannelType() {
        return "telegram";
    }

    @Override
    public void start() {
        // Инициализация Telegram Bot API
        // Запуск long-polling
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        // Остановка polling, закрытие соединений
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public CompletableFuture<Void> sendMessage(String chatId, String content) {
        // bot.execute(new SendMessage(chatId, content));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> sendMessage(Message message) {
        return sendMessage(message.getChatId(), message.getContent());
    }

    @Override
    public CompletableFuture<Void> sendVoice(String chatId, byte[] voiceData) {
        // bot.execute(new SendVoice(chatId, voiceData));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public boolean isAuthorized(String senderId) {
        // Проверка allowedUsers из конфигурации
        return true;
    }

    @Override
    public void onMessage(Consumer<Message> handler) {
        messageHandlers.add(handler);
    }

    @Override
    public void showTyping(String chatId) {
        // bot.execute(new SendChatAction(chatId, ChatAction.typing));
    }
}
```

### Пример 3: LLM-провайдер (Ollama)

```java
// OllamaPlugin.java
package me.golemcore.plugin.ollama;

import org.pf4j.Plugin;

public class OllamaPlugin extends Plugin { }
```

```java
// OllamaAdapter.java
package me.golemcore.plugin.ollama;

import me.golemcore.bot.domain.model.LlmRequest;
import me.golemcore.bot.domain.model.LlmResponse;
import me.golemcore.bot.port.outbound.LlmProviderAdapter;
import org.pf4j.Extension;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Extension
public class OllamaAdapter implements LlmProviderAdapter {

    @Override
    public String getProviderId() {
        return "ollama";
    }

    @Override
    public CompletableFuture<LlmResponse> chat(LlmRequest request) {
        // HTTP POST http://localhost:11434/api/chat
        // ...
    }

    @Override
    public List<String> getSupportedModels() {
        return List.of("llama3", "mistral", "codellama", "phi3");
    }

    @Override
    public String getCurrentModel() {
        return System.getenv().getOrDefault("OLLAMA_MODEL", "llama3");
    }

    @Override
    public boolean isAvailable() {
        // Проверяем доступность Ollama по HTTP
        return true;
    }
}
```

### Пример 4: Pipeline-система (модерация)

Плагин, добавляющий свою систему в pipeline агентского цикла:

```java
// ModerationPlugin.java
package me.golemcore.plugin.moderation;

import org.pf4j.Plugin;

public class ModerationPlugin extends Plugin { }
```

```java
// ContentModerationSystem.java
package me.golemcore.plugin.moderation;

import me.golemcore.bot.domain.model.AgentContext;
import me.golemcore.bot.domain.system.AgentSystem;
import org.pf4j.Extension;

/**
 * Система модерации контента.
 * Вставляется в pipeline между InputSanitization (10) и ContextBuilding (20).
 */
@Extension
public class ContentModerationSystem implements AgentSystem {

    @Override
    public String getName() {
        return "ContentModeration";
    }

    @Override
    public int getOrder() {
        return 15; // Между InputSanitization (10) и ContextBuilding (20)
    }

    @Override
    public AgentContext process(AgentContext context) {
        // Проверка сообщения пользователя через модерационное API
        // Блокировка или модификация при необходимости
        return context;
    }

    @Override
    public boolean isEnabled() {
        return System.getenv("MODERATION_API_KEY") != null;
    }
}
```

---

## Управление плагинами в ядре (PluginManager)

### GolemPluginManager

Обёртка над PF4J `SpringPluginManager`, интегрирующая плагины с существующей системой обнаружения компонентов.

```java
package me.golemcore.bot.infrastructure.plugin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.system.AgentSystem;
import me.golemcore.bot.infrastructure.config.BotProperties;
import me.golemcore.bot.port.inbound.ChannelPort;
import me.golemcore.bot.port.outbound.LlmProviderAdapter;
import me.golemcore.bot.port.outbound.VoicePort;
import me.golemcore.bot.port.outbound.BrowserPort;
import me.golemcore.bot.port.outbound.RagPort;
import org.pf4j.PluginManager;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;
import org.pf4j.spring.SpringPluginManager;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.util.List;

@Component
@Slf4j
public class GolemPluginManager {

    private final BotProperties botProperties;
    private final SpringPluginManager pluginManager;

    public GolemPluginManager(BotProperties botProperties) {
        this.botProperties = botProperties;
        // PF4J ищет плагины в указанной директории
        Path pluginsDir = Path.of(botProperties.getPlugins().getPath());
        this.pluginManager = new SpringPluginManager(pluginsDir);
    }

    @PostConstruct
    public void init() {
        if (!botProperties.getPlugins().isEnabled()) {
            log.info("[plugins] Plugin system disabled");
            return;
        }

        log.info("[plugins] Loading plugins from: {}", botProperties.getPlugins().getPath());
        pluginManager.loadPlugins();
        pluginManager.startPlugins();

        // Логирование загруженных плагинов
        for (PluginWrapper plugin : pluginManager.getStartedPlugins()) {
            log.info("[plugins] Loaded: {} v{} ({})",
                    plugin.getPluginId(),
                    plugin.getDescriptor().getVersion(),
                    plugin.getDescriptor().getPluginDescription());
        }

        logExtensions();
    }

    @PreDestroy
    public void shutdown() {
        pluginManager.stopPlugins();
    }

    // ─── Получение Extensions по типу ─────────────────────

    public List<ToolComponent> getTools() {
        return pluginManager.getExtensions(ToolComponent.class);
    }

    public List<ChannelPort> getChannels() {
        return pluginManager.getExtensions(ChannelPort.class);
    }

    public List<LlmProviderAdapter> getLlmAdapters() {
        return pluginManager.getExtensions(LlmProviderAdapter.class);
    }

    public List<VoicePort> getVoiceAdapters() {
        return pluginManager.getExtensions(VoicePort.class);
    }

    public List<BrowserPort> getBrowserAdapters() {
        return pluginManager.getExtensions(BrowserPort.class);
    }

    public List<RagPort> getRagAdapters() {
        return pluginManager.getExtensions(RagPort.class);
    }

    public List<AgentSystem> getSystems() {
        return pluginManager.getExtensions(AgentSystem.class);
    }

    // ─── Управление плагинами ─────────────────────────────

    /** Установка плагина из JAR-файла. */
    public String installPlugin(Path pluginJar) {
        String pluginId = pluginManager.loadPlugin(pluginJar);
        if (pluginId != null) {
            pluginManager.startPlugin(pluginId);
            log.info("[plugins] Installed and started: {}", pluginId);
        }
        return pluginId;
    }

    /** Удаление плагина. */
    public boolean uninstallPlugin(String pluginId) {
        PluginState state = pluginManager.stopPlugin(pluginId);
        if (state == PluginState.STOPPED) {
            boolean deleted = pluginManager.deletePlugin(pluginId);
            log.info("[plugins] Uninstalled: {} (deleted={})", pluginId, deleted);
            return deleted;
        }
        return false;
    }

    /** Перезагрузка плагина (stop → unload → load → start). */
    public boolean reloadPlugin(String pluginId) {
        pluginManager.stopPlugin(pluginId);
        pluginManager.unloadPlugin(pluginId);
        String reloadedId = pluginManager.loadPlugin(
                pluginManager.getPlugin(pluginId).getPluginPath());
        if (reloadedId != null) {
            pluginManager.startPlugin(reloadedId);
            return true;
        }
        return false;
    }

    /** Включение/выключение плагина. */
    public void enablePlugin(String pluginId) {
        pluginManager.enablePlugin(pluginId);
        pluginManager.startPlugin(pluginId);
    }

    public void disablePlugin(String pluginId) {
        pluginManager.stopPlugin(pluginId);
        pluginManager.disablePlugin(pluginId);
    }

    /** Информация о всех плагинах. */
    public List<PluginWrapper> getPlugins() {
        return pluginManager.getPlugins();
    }

    public PluginWrapper getPlugin(String pluginId) {
        return pluginManager.getPlugin(pluginId);
    }

    // ─── Внутренние методы ────────────────────────────────

    private void logExtensions() {
        log.info("[plugins] Extensions — tools: {}, channels: {}, llm: {}, voice: {}, browser: {}, rag: {}, systems: {}",
                getTools().size(),
                getChannels().size(),
                getLlmAdapters().size(),
                getVoiceAdapters().size(),
                getBrowserAdapters().size(),
                getRagAdapters().size(),
                getSystems().size());
    }
}
```

### PluginBridge — интеграция с существующими точками обнаружения

Текущее ядро использует `List<ToolComponent>`, `List<ChannelPort>` и т.д., инжектируемые через конструкторы. `PluginBridge` объединяет встроенные компоненты и плагинные:

```java
package me.golemcore.bot.infrastructure.plugin;

import lombok.RequiredArgsConstructor;
import me.golemcore.bot.domain.component.ToolComponent;
import me.golemcore.bot.domain.system.AgentSystem;
import me.golemcore.bot.port.inbound.ChannelPort;
import me.golemcore.bot.port.outbound.LlmProviderAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.List;

/**
 * Мост между встроенными Spring-компонентами и плагинными Extension-ами.
 *
 * Создаёт объединённые списки, которые AgentLoop, ToolCallExecutionService
 * и LlmAdapterFactory получают через конструкторы.
 */
@Configuration
@RequiredArgsConstructor
public class PluginBridge {

    private final GolemPluginManager golemPluginManager;

    /**
     * Объединённый список всех ToolComponent: встроенные + из плагинов.
     * Для ToolCallExecutionService.
     */
    @Bean
    @Primary
    public static List<ToolComponent> allToolComponents(
            List<ToolComponent> builtinTools,
            GolemPluginManager pluginManager) {
        List<ToolComponent> all = new ArrayList<>(builtinTools);
        all.addAll(pluginManager.getTools());
        return all;
    }

    /**
     * Объединённый список всех ChannelPort: встроенные + из плагинов.
     * Для AgentLoop.
     */
    @Bean
    @Primary
    public static List<ChannelPort> allChannelPorts(
            List<ChannelPort> builtinChannels,
            GolemPluginManager pluginManager) {
        List<ChannelPort> all = new ArrayList<>(builtinChannels);
        all.addAll(pluginManager.getChannels());
        return all;
    }

    // Аналогично для LlmProviderAdapter, AgentSystem, etc.
}
```

**Важно:** `@Bean` методы объявлены `static` — это правило проекта для `@Configuration`-классов с зависимостями (избегаем circular deps).

---

## Маркетплейс

### Архитектура маркетплейса

Маркетплейс — это **JSON-реестр**, размещённый как статический файл. Ядро периодически скачивает реестр и показывает доступные плагины в дашборде.

```
                    GitHub Pages / CDN
                    ┌────────────────────────┐
                    │  plugin-registry.json  │
                    │                        │
                    │  Список всех плагинов  │
                    │  с версиями, ссылками, │
                    │  описаниями            │
                    └───────────┬────────────┘
                                │
                   GET (периодически)
                                │
                                ▼
┌──────────────── GolemCore Bot ────────────────┐
│                                               │
│  PluginRegistryService                        │
│    │                                          │
│    ├─ Кеширует реестр в памяти               │
│    ├─ Сравнивает с установленными плагинами  │
│    └─ Предоставляет данные для Dashboard     │
│                                               │
│  REST API:                                    │
│    GET /api/plugins/marketplace               │
│    POST /api/plugins/install?id=...&version=  │
└───────────────────────────────────────────────┘
```

### Формат реестра (plugin-registry.json)

```json
{
  "schemaVersion": 1,
  "updatedAt": "2026-02-18T12:00:00Z",
  "coreCompatibility": ">=0.2.0",
  "plugins": [
    {
      "id": "golemcore-telegram",
      "name": "Telegram",
      "description": "Telegram channel adapter with voice messages, inline keyboards and menu support",
      "category": "CHANNEL",
      "provider": "GolemCore Team",
      "license": "Apache-2.0",
      "homepage": "https://github.com/alexk-dev/golemcore-plugins/tree/main/telegram-plugin",
      "icon": "https://golemcore.me/icons/telegram.svg",
      "tags": ["channel", "telegram", "voice", "official"],
      "versions": [
        {
          "version": "1.2.0",
          "releaseDate": "2026-02-18",
          "requires": ">=0.2.0",
          "downloadUrl": "https://github.com/alexk-dev/golemcore-plugins/releases/download/telegram-v1.2.0/golemcore-telegram-plugin-1.2.0.jar",
          "sha256": "a1b2c3d4e5f6...",
          "size": 2456789,
          "changelog": "- Add inline keyboard support\n- Fix voice message encoding"
        },
        {
          "version": "1.1.0",
          "releaseDate": "2026-02-10",
          "requires": ">=0.2.0",
          "downloadUrl": "https://github.com/alexk-dev/golemcore-plugins/releases/download/telegram-v1.1.0/golemcore-telegram-plugin-1.1.0.jar",
          "sha256": "f6e5d4c3b2a1...",
          "size": 2345678,
          "changelog": "- Add voice message support"
        }
      ]
    },
    {
      "id": "golemcore-brave-search",
      "name": "Brave Search",
      "description": "Web search tool powered by Brave Search API",
      "category": "TOOL",
      "provider": "GolemCore Team",
      "license": "Apache-2.0",
      "homepage": "https://github.com/alexk-dev/golemcore-plugins/tree/main/brave-search-plugin",
      "icon": "https://golemcore.me/icons/brave.svg",
      "tags": ["tool", "search", "web", "official"],
      "versions": [
        {
          "version": "1.1.0",
          "releaseDate": "2026-02-15",
          "requires": ">=0.2.0",
          "downloadUrl": "https://github.com/alexk-dev/golemcore-plugins/releases/download/brave-v1.1.0/golemcore-brave-search-plugin-1.1.0.jar",
          "sha256": "...",
          "size": 156789,
          "changelog": "- Add count parameter\n- Improve error handling"
        }
      ]
    },
    {
      "id": "golemcore-elevenlabs",
      "name": "ElevenLabs Voice",
      "description": "Speech-to-text and text-to-speech via ElevenLabs API",
      "category": "VOICE",
      "provider": "GolemCore Team",
      "license": "Apache-2.0",
      "tags": ["voice", "stt", "tts", "elevenlabs", "official"],
      "versions": [
        {
          "version": "1.0.0",
          "releaseDate": "2026-02-12",
          "requires": ">=0.2.0",
          "downloadUrl": "https://github.com/alexk-dev/golemcore-plugins/releases/download/elevenlabs-v1.0.0/golemcore-elevenlabs-plugin-1.0.0.jar",
          "sha256": "...",
          "size": 189000,
          "changelog": "- Initial release"
        }
      ]
    }
  ]
}
```

### Публикация реестра

Реестр обновляется автоматически CI-пайплайном репозитория `golemcore-plugins`:

```yaml
# golemcore-plugins/.github/workflows/update-registry.yml
name: Update Plugin Registry

on:
  release:
    types: [published]

jobs:
  update-registry:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v6
        with:
          ref: gh-pages  # реестр хранится в ветке gh-pages

      - name: Update registry with new release
        run: |
          # Скрипт читает все releases через GitHub API,
          # генерирует plugin-registry.json
          python scripts/update-registry.py

      - name: Commit and push
        run: |
          git add plugin-registry.json
          git commit -m "chore: update registry for ${{ github.event.release.tag_name }}"
          git push
```

Итоговый URL: `https://alexk-dev.github.io/golemcore-plugins/plugin-registry.json`

### PluginRegistryService

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class PluginRegistryService {

    private final BotProperties botProperties;
    private final GolemPluginManager pluginManager;

    private volatile PluginRegistry cachedRegistry;

    /**
     * Периодическая загрузка реестра.
     */
    @Scheduled(fixedDelayString = "${bot.plugins.registry-check-interval:PT6H}")
    public void refreshRegistry() {
        // GET registryUrl
        // Десериализовать в PluginRegistry
        // Сохранить в cachedRegistry
    }

    /**
     * Список плагинов для маркетплейса.
     * Сопоставляет доступные плагины с установленными.
     */
    public List<MarketplaceEntry> getMarketplace() {
        // Для каждого плагина в реестре:
        //   - installed: boolean (есть ли в pluginManager)
        //   - installedVersion: String (если установлен)
        //   - updateAvailable: boolean (есть ли более новая версия)
        //   - compatible: boolean (совместим ли с текущим ядром)
    }

    /**
     * Установка плагина из маркетплейса.
     */
    public InstallResult install(String pluginId, String version) {
        // 1. Найти плагин и версию в реестре
        // 2. Скачать JAR по downloadUrl
        // 3. Проверить SHA-256
        // 4. Поместить в plugins/ директорию
        // 5. pluginManager.installPlugin(path)
        // 6. Вернуть результат
    }
}
```

---

## REST API управления плагинами

### Эндпоинты

```
GET    /api/plugins                        — список установленных плагинов
GET    /api/plugins/{id}                   — информация о конкретном плагине
POST   /api/plugins/{id}/enable            — включить плагин
POST   /api/plugins/{id}/disable           — выключить плагин
POST   /api/plugins/{id}/reload            — перезагрузить плагин
DELETE /api/plugins/{id}                   — удалить плагин
GET    /api/plugins/marketplace            — каталог доступных плагинов
POST   /api/plugins/marketplace/install    — установить плагин из маркетплейса
POST   /api/plugins/marketplace/update     — обновить плагин до последней версии
POST   /api/plugins/upload                 — загрузить плагин вручную (upload JAR)
```

### Формат ответов

#### GET /api/plugins

```json
{
  "plugins": [
    {
      "id": "golemcore-telegram",
      "version": "1.2.0",
      "state": "STARTED",
      "provider": "GolemCore Team",
      "description": "Telegram channel adapter with voice, menus and inline keyboards",
      "requires": ">=0.2.0",
      "extensions": {
        "channelPorts": ["telegram"],
        "tools": [],
        "llmProviders": [],
        "systems": []
      }
    },
    {
      "id": "golemcore-brave-search",
      "version": "1.1.0",
      "state": "STARTED",
      "provider": "GolemCore Team",
      "description": "Web search tool powered by Brave Search API",
      "requires": ">=0.2.0",
      "extensions": {
        "channelPorts": [],
        "tools": ["brave_search"],
        "llmProviders": [],
        "systems": []
      }
    }
  ]
}
```

#### GET /api/plugins/marketplace

```json
{
  "plugins": [
    {
      "id": "golemcore-ollama",
      "name": "Ollama",
      "description": "Local LLM inference via Ollama",
      "category": "LLM_PROVIDER",
      "latestVersion": "1.0.0",
      "installed": false,
      "installedVersion": null,
      "updateAvailable": false,
      "compatible": true,
      "tags": ["llm", "local", "ollama"]
    },
    {
      "id": "golemcore-telegram",
      "name": "Telegram",
      "description": "Telegram channel adapter",
      "category": "CHANNEL",
      "latestVersion": "1.3.0",
      "installed": true,
      "installedVersion": "1.2.0",
      "updateAvailable": true,
      "compatible": true,
      "tags": ["channel", "telegram", "official"]
    }
  ]
}
```

#### POST /api/plugins/marketplace/install

Request:
```json
{
  "pluginId": "golemcore-ollama",
  "version": "1.0.0"
}
```

Response:
```json
{
  "success": true,
  "pluginId": "golemcore-ollama",
  "version": "1.0.0",
  "message": "Plugin installed and started",
  "extensions": {
    "llmProviders": ["ollama"]
  }
}
```

---

## Slash-команда /plugins

### Синтаксис

```
/plugins                     — список установленных плагинов
/plugins marketplace         — каталог доступных плагинов
/plugins install <id>        — установить плагин из маркетплейса
/plugins uninstall <id>      — удалить плагин
/plugins enable <id>         — включить плагин
/plugins disable <id>        — выключить плагин
/plugins update <id>         — обновить плагин до последней версии
/plugins update all          — обновить все плагины
```

### Пример вывода

```
/plugins
────────────────────────────
🔌 Установленные плагины (3):

  ✅ golemcore-telegram v1.2.0
     Telegram channel adapter
     Extensions: channel[telegram]

  ✅ golemcore-brave-search v1.1.0
     Web search tool
     Extensions: tool[brave_search]

  ⏸ golemcore-elevenlabs v1.0.0 (выключен)
     ElevenLabs Voice
     Extensions: voice[elevenlabs]
────────────────────────────
```

```
/plugins marketplace
────────────────────────────
📦 Доступные плагины:

  Каналы:
    golemcore-discord v1.0.0 — Discord channel adapter

  Инструменты:
    golemcore-weather v1.0.0 — Weather API tool

  LLM:
    golemcore-ollama v1.0.0 — Local LLM via Ollama

  /plugins install <id> — установить
────────────────────────────
```

---

## Dashboard UI

### Страница «Плагины» (Plugins)

```
┌─────────────────────────────────────────────────────┐
│  Plugins                                    [Upload]│
│                                                     │
│  ┌─── Installed ──────────────────────────────────┐ │
│  │                                                │ │
│  │  ☑ Telegram v1.2.0          [Disable] [Delete]│ │
│  │    Channel: telegram                           │ │
│  │    ⬆ Update available: v1.3.0   [Update]      │ │
│  │                                                │ │
│  │  ☑ Brave Search v1.1.0     [Disable] [Delete] │ │
│  │    Tool: brave_search                          │ │
│  │    ✅ Up to date                               │ │
│  │                                                │ │
│  │  ☐ ElevenLabs v1.0.0       [Enable]  [Delete] │ │
│  │    Voice: elevenlabs (disabled)                │ │
│  │                                                │ │
│  └────────────────────────────────────────────────┘ │
│                                                     │
│  ┌─── Marketplace ────────────────────────────────┐ │
│  │                                                │ │
│  │  🔎 [Search plugins...                       ] │ │
│  │  Filter: [All] [Channel] [Tool] [LLM] [Voice] │ │
│  │                                                │ │
│  │  ┌──────────────────────────────────────────┐  │ │
│  │  │ 🤖 Ollama                    [Install]   │  │ │
│  │  │ Local LLM inference                      │  │ │
│  │  │ v1.0.0 · LLM_PROVIDER · GolemCore Team  │  │ │
│  │  └──────────────────────────────────────────┘  │ │
│  │                                                │ │
│  │  ┌──────────────────────────────────────────┐  │ │
│  │  │ 💬 Discord                   [Install]   │  │ │
│  │  │ Discord channel adapter                  │  │ │
│  │  │ v1.0.0 · CHANNEL · GolemCore Team       │  │ │
│  │  └──────────────────────────────────────────┘  │ │
│  │                                                │ │
│  └────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────┘
```

---

## Конфигурация плагинов

### BotProperties: секция plugins

```java
private PluginsProperties plugins = new PluginsProperties();

@Data
public static class PluginsProperties {
    /** Включена ли плагинная система. */
    private boolean enabled = false;

    /** Путь к директории с плагинами. */
    private String path = "/data/plugins";

    /** URL реестра плагинов (маркетплейс). */
    private String registryUrl = "https://alexk-dev.github.io/golemcore-plugins/plugin-registry.json";

    /** Интервал проверки обновлений реестра. */
    private java.time.Duration registryCheckInterval = java.time.Duration.ofHours(6);

    /** Разрешить загрузку плагинов через Upload (ручная установка JAR). */
    private boolean allowUpload = true;

    /** Разрешить установку плагинов из маркетплейса. */
    private boolean allowMarketplace = true;
}
```

### application.properties

```properties
bot.plugins.enabled=${BOT_PLUGINS_ENABLED:false}
bot.plugins.path=${PLUGINS_PATH:/data/plugins}
bot.plugins.registry-url=${BOT_PLUGINS_REGISTRY_URL:https://alexk-dev.github.io/golemcore-plugins/plugin-registry.json}
bot.plugins.registry-check-interval=PT6H
bot.plugins.allow-upload=true
bot.plugins.allow-marketplace=true
```

### Конфигурация отдельных плагинов

Каждый плагин получает конфигурацию через `PluginContext.getConfiguration()`. Значения берутся из:

1. **Переменные окружения** (приоритет 1): `PLUGIN_TELEGRAM_TOKEN`, `PLUGIN_BRAVE_API_KEY`
2. **runtime-config.json** (приоритет 2): секция `plugins.{pluginId}.*`
3. **Значения по умолчанию** из плагина (приоритет 3)

Конвенция: переменные окружения с префиксом `PLUGIN_{PLUGIN_ID_UPPER}_*`:

```bash
# Для плагина golemcore-telegram:
PLUGIN_TELEGRAM_TOKEN=123456:ABC...
PLUGIN_TELEGRAM_ALLOWED_USERS=123,456

# Для плагина golemcore-brave-search:
PLUGIN_BRAVE_API_KEY=BSA...

# Для плагина golemcore-elevenlabs:
PLUGIN_ELEVENLABS_API_KEY=sk_...
PLUGIN_ELEVENLABS_VOICE_ID=...
```

---

## Изоляция и ClassLoader

### Как PF4J изолирует плагины

Каждый плагин загружается своим `PluginClassLoader`:

```
Application ClassLoader (ядро + golemcore-api)
    │
    ├── PluginClassLoader (golemcore-telegram)
    │   └── telegram-bot-api.jar, plugin classes
    │
    ├── PluginClassLoader (golemcore-brave-search)
    │   └── plugin classes
    │
    └── PluginClassLoader (golemcore-elevenlabs)
        └── httpclient, plugin classes
```

### Режим ClassLoader: Parent-First

PF4J поддерживает два режима:
- **Parent-First** (по умолчанию): сначала ищет класс в parent (ядро), потом в плагине. Плагин использует те же версии общих библиотек, что и ядро.
- **Plugin-First**: сначала ищет в плагине, потом в parent. Позволяет плагину использовать свою версию библиотеки.

Рекомендуемый режим: **Parent-First** — проще, предсказуемее, избегает конфликтов.

### Что видит плагин

| Класс/пакет | Видим? | Источник |
|-------------|--------|----------|
| `me.golemcore.bot.domain.component.*` | Да | golemcore-api (parent) |
| `me.golemcore.bot.domain.model.*` | Да | golemcore-api (parent) |
| `me.golemcore.bot.port.*` | Да | golemcore-api (parent) |
| `me.golemcore.bot.domain.service.*` | Нет | golemcore-core (не в API) |
| `me.golemcore.bot.domain.loop.*` | Нет | golemcore-core (не в API) |
| `me.golemcore.bot.infrastructure.*` | Нет | golemcore-core (не в API) |
| Собственные классы плагина | Да | PluginClassLoader |
| Зависимости плагина (shade) | Да | PluginClassLoader |
| `org.springframework.*` | Да | Parent (если плагин их использует) |

---

## Зависимости между плагинами

PF4J поддерживает зависимости через `Plugin-Dependencies` в манифесте:

```
Plugin-Dependencies: golemcore-telegram@>=1.0.0
```

### Пример: плагин, зависящий от другого

Допустим, `telegram-voice-plugin` расширяет `telegram-plugin`:

```
Plugin-Id: golemcore-telegram-voice
Plugin-Dependencies: golemcore-telegram@>=1.2.0
```

PF4J гарантирует:
1. `golemcore-telegram` загружается и запускается **до** `golemcore-telegram-voice`
2. Если `golemcore-telegram` не установлен или версия < 1.2.0 — `golemcore-telegram-voice` не загрузится
3. При удалении `golemcore-telegram` — зависимые плагины останавливаются

### Рекомендация

Минимизировать зависимости между плагинами. Каждый плагин должен быть максимально самодостаточным. Зависимости между плагинами усложняют управление и обновление.

---

## Версионирование и совместимость

### Версия SDK (golemcore-api)

`golemcore-api` следует Semantic Versioning:

- **MAJOR** (0.x → 1.0): ломающие изменения в интерфейсах (удаление методов, изменение сигнатур)
- **MINOR** (0.2 → 0.3): новые методы с `default`-реализацией в интерфейсах, новые модели
- **PATCH** (0.2.0 → 0.2.1): исправления багов в моделях, без изменения интерфейсов

### Совместимость плагинов

`Plugin-Requires: >=0.2.0` означает: «этот плагин совместим с golemcore-api версии 0.2.0 и выше».

При загрузке плагина `GolemPluginManager` проверяет:
1. `Plugin-Requires` совместим с текущей версией ядра
2. `Plugin-Dependencies` удовлетворены (все зависимые плагины установлены)
3. Нет конфликтов с уже загруженными плагинами (дублирующие plugin-id)

### Правила обратной совместимости SDK

1. **Никогда не удалять** методы из интерфейсов в пределах одного MAJOR.
2. **Новые методы** добавлять только с `default`-реализацией.
3. **Новые поля** в моделях добавлять как `Optional` или с значением по умолчанию.
4. **Не менять** семантику существующих методов.

Пример безопасного расширения:

```java
// golemcore-api v0.2.0
public interface ChannelPort {
    CompletableFuture<Void> sendMessage(String chatId, String content);
}

// golemcore-api v0.3.0 — обратно-совместимое добавление
public interface ChannelPort {
    CompletableFuture<Void> sendMessage(String chatId, String content);

    // Новый метод с default — плагины v0.2.0 работают без изменений
    default CompletableFuture<Void> sendReaction(String chatId, String messageId, String emoji) {
        return CompletableFuture.completedFuture(null); // no-op
    }
}
```

---

## Безопасность

### Подпись и проверка целостности

Каждый JAR-плагин сопровождается SHA-256 контрольной суммой в реестре. При установке из маркетплейса:

1. Скачивается JAR
2. Вычисляется SHA-256
3. Сравнивается с `sha256` из реестра
4. Если не совпадает — установка отклоняется

### Песочница для плагинов

Плагины не имеют доступа к:
- Внутренним сервисам ядра (только через `PluginContext`)
- Данным других плагинов (каждый плагин работает в своей директории `plugins/{pluginId}/`)
- Конфигурации ядра (только свои переменные `PLUGIN_{ID}_*`)

### SecurityManager (будущее)

При необходимости можно ограничить плагины через Java SecurityManager или модульную систему (JPMS):
- Запретить доступ к файловой системе за пределами своей директории
- Ограничить сетевые подключения
- Запретить System.exit(), Runtime.exec()

Это расширение на будущее — PF4J поддерживает кастомные `PluginClassLoader` с ограничениями.

### Ручная загрузка (Upload)

При ручной загрузке JAR через REST API (`/api/plugins/upload`):
- Файл сохраняется во временную директорию
- Проверяется наличие валидного MANIFEST.MF с PF4J-атрибутами
- Проверяется `Plugin-Requires` на совместимость
- Только после всех проверок файл перемещается в `plugins/` и загружается

Администратор может отключить ручную загрузку: `bot.plugins.allow-upload=false`.

---

## Миграция существующих компонентов

### Что станет плагином

| Текущий компонент | Плагин ID | Категория | Сложность миграции |
|-------------------|-----------|-----------|-------------------|
| `TelegramAdapter` + `TelegramVoiceHandler` + `TelegramMenuHandler` | `golemcore-telegram` | CHANNEL | Высокая (3 класса, callback-механизмы) |
| `WebChannelAdapter` + `WebSocketChatHandler` | `golemcore-web-channel` | CHANNEL | Средняя |
| `WebhookChannelAdapter` + `WebhookController` + ... | `golemcore-webhooks` | CHANNEL | Высокая (5+ классов) |
| `Langchain4jAdapter` | `golemcore-langchain4j` | LLM_PROVIDER | Средняя (основной LLM-адаптер) |
| `ElevenLabsAdapter` | `golemcore-elevenlabs` | VOICE | Низкая (один класс) |
| `PlaywrightAdapter` | `golemcore-playwright` | BROWSER | Низкая (один класс) |
| `BraveSearchTool` | `golemcore-brave-search` | TOOL | Низкая (один класс) |
| `WeatherTool` | `golemcore-weather` | TOOL | Низкая (один класс) |
| `ImapTool` + `SmtpTool` | `golemcore-email` | TOOL | Низкая (два класса) |
| `LightRagAdapter` | `golemcore-lightrag` | RAG | Низкая (один класс) |

### Что останется в ядре

| Компонент | Почему в ядре |
|-----------|---------------|
| `AgentLoop` | Центральный pipeline — фундамент |
| Все `*System` (pipeline) | Критическая бизнес-логика |
| `SessionService` | Управление состоянием |
| `SkillService` | Загрузка и управление скиллами |
| `CompactionService` | Критическая для стабильности |
| `CommandRouter` | Маршрутизация команд (расширяемая плагинами) |
| `LocalStorageAdapter` | Базовое хранилище |
| `InjectionGuard`, `InputSanitizer` | Безопасность |
| `DateTimeTool`, `PlanGetTool`, `PlanSetContentTool` | Базовые инструменты, тесно связанные с ядром |
| `ToolCallExecutionService` | Реестр и исполнение инструментов |
| `GolemPluginManager` | Управление плагинами |

### Порядок миграции

Рекомендуемый порядок — от простого к сложному:

1. **BraveSearchTool** — один класс, один Extension Point (`ToolComponent`), минимум зависимостей. Идеальный первый плагин для проверки всей инфраструктуры.

2. **WeatherTool** — аналогично, простой инструмент.

3. **ElevenLabsAdapter** — один класс, `VoicePort`, несколько HTTP-вызовов.

4. **PlaywrightAdapter** — один класс, `BrowserPort`, зависимость от Playwright.

5. **ImapTool + SmtpTool** — два класса в одном плагине, `ToolComponent`.

6. **LightRagAdapter** — один класс, `RagPort`.

7. **Langchain4jAdapter** — сложнее: зависимости langchain4j, конфигурация моделей. Может остаться встроенным или стать плагином.

8. **TelegramAdapter** — самый сложный: три класса, callback-механизмы, inline keyboards, voice handler.

---

## Docker-деплой с плагинами

### Docker Compose

```yaml
services:
  golemcore-bot:
    image: ghcr.io/alexk-dev/golemcore-bot:latest
    container_name: golemcore-bot
    restart: unless-stopped
    environment:
      STORAGE_PATH: /app/workspace
      TOOLS_WORKSPACE: /app/sandbox

      # Плагины
      BOT_PLUGINS_ENABLED: "true"
      PLUGINS_PATH: /data/plugins

      # Конфигурация плагинов
      PLUGIN_TELEGRAM_TOKEN: "${TELEGRAM_TOKEN}"
      PLUGIN_TELEGRAM_ALLOWED_USERS: "${TELEGRAM_ALLOWED_USERS}"
      PLUGIN_BRAVE_API_KEY: "${BRAVE_API_KEY}"
      PLUGIN_ELEVENLABS_API_KEY: "${ELEVENLABS_API_KEY}"
    volumes:
      - bot-workspace:/app/workspace
      - bot-sandbox:/app/sandbox
      - bot-plugins:/data/plugins      # Плагины
      - bot-updates:/data/updates      # Обновления ядра
    ports:
      - "8080:8080"

volumes:
  bot-workspace:
  bot-sandbox:
  bot-plugins:
  bot-updates:
```

### Предустановленные плагины в Docker-образе

Для удобства базовый Docker-образ может включать набор «стандартных» плагинов:

```xml
<!-- golemcore-app/pom.xml — Jib extraDirectories -->
<extraDirectories>
    <paths>
        <path>
            <from>misc/docker</from>
            <into>/</into>
        </path>
        <path>
            <from>misc/bundled-plugins</from>
            <into>/data/plugins</into>
        </path>
    </paths>
</extraDirectories>
```

```
misc/bundled-plugins/
├── golemcore-telegram-plugin-1.2.0.jar
├── golemcore-langchain4j-plugin-1.0.0.jar
└── golemcore-brave-search-plugin-1.1.0.jar
```

Пользователь может удалить ненужные и установить дополнительные через маркетплейс.

---

## Интеграция с механизмом автообновления

Система автообновления ядра (см. раздел self-update в [CONFIGURATION.md](CONFIGURATION.md)) и плагинная система дополняют друг друга:

| Механизм | Что обновляется | Рестарт |
|----------|-----------------|---------|
| Self-Update (ядро) | golemcore-core + golemcore-api | Да (exit 42) |
| Plugin Update | Отдельный плагин | Нет (hot-reload через PF4J) |
| Docker Update | Всё (образ) | Да (весь контейнер) |

### Hot-reload плагинов (без рестарта JVM)

PF4J поддерживает загрузку и выгрузку плагинов без перезапуска JVM:

```
1. pluginManager.stopPlugin("golemcore-telegram")
2. pluginManager.unloadPlugin("golemcore-telegram")
3. Заменить JAR на новый
4. pluginManager.loadPlugin(newJarPath)
5. pluginManager.startPlugin("golemcore-telegram")
```

Это позволяет обновлять плагины **без downtime**. Единственная потеря — текущие активные соединения плагина (например, Telegram long-polling пересоздаётся).

### PluginUpdateService

```java
/**
 * Проверяет обновления плагинов в реестре.
 * Аналогичен UpdateService для ядра, но работает с plugin-registry.json.
 */
@Scheduled(fixedDelayString = "${bot.plugins.registry-check-interval:PT6H}")
public void checkPluginUpdates() {
    // 1. Загрузить реестр
    // 2. Для каждого установленного плагина:
    //    - Сравнить версию с последней в реестре
    //    - Если есть обновление — уведомить
    // 3. Если auto-update включён — скачать и применить
}
```

---

## План внедрения

### Фаза 0: Подготовка (не ломает текущий функционал)

1. Разбить проект на Maven-модули: `golemcore-api`, `golemcore-core`, `golemcore-app`
2. Перенести интерфейсы и модели в `golemcore-api`
3. Убедиться, что всё собирается и тесты проходят
4. Опубликовать `golemcore-api` в GitHub Packages

### Фаза 1: PF4J инфраструктура

5. Добавить PF4J и `pf4j-spring` зависимости в `golemcore-core`
6. Создать `GolemPluginManager`
7. Создать `PluginBridge` для интеграции с существующим обнаружением
8. Добавить конфигурацию `bot.plugins.*`
9. Создать REST API `/api/plugins`
10. Добавить команду `/plugins`

### Фаза 2: Первый плагин (proof of concept)

11. Создать репозиторий `golemcore-plugins`
12. Вынести `BraveSearchTool` в `brave-search-plugin`
13. Протестировать полный цикл: сборка → установка → работа → обновление → удаление
14. Документировать процесс создания плагина (README для контрибьюторов)

### Фаза 3: Миграция инструментов

15. Вынести `WeatherTool` → `weather-plugin`
16. Вынести `ImapTool` + `SmtpTool` → `email-plugin`
17. Вынести `ElevenLabsAdapter` → `elevenlabs-plugin`
18. Вынести `PlaywrightAdapter` → `playwright-plugin`
19. Вынести `LightRagAdapter` → `lightrag-plugin`

### Фаза 4: Маркетплейс

20. Создать `plugin-registry.json` и CI для его обновления
21. Реализовать `PluginRegistryService`
22. Добавить страницу Marketplace в Dashboard
23. Реализовать установку/обновление из маркетплейса

### Фаза 5: Каналы и LLM

24. Вынести `TelegramAdapter` → `telegram-plugin`
25. Опционально: вынести `Langchain4jAdapter` → `langchain4j-plugin`
26. Вынести `WebChannelAdapter` → `web-channel-plugin`
27. Вынести `WebhookChannelAdapter` → `webhooks-plugin`

### Фаза 6: Экосистема

28. Опубликовать `golemcore-api` в Maven Central
29. Написать документацию для сторонних разработчиков
30. Создать шаблон (`archetype`) для быстрого создания нового плагина
31. Добавить рейтинги и отзывы в маркетплейс (опционально)

---

## Список файлов

### Новые файлы (golemcore-bot)

| Файл | Описание |
|------|----------|
| `golemcore-api/pom.xml` | POM модуля SDK |
| `golemcore-core/pom.xml` | POM модуля ядра |
| `golemcore-app/pom.xml` | POM модуля приложения |
| `infrastructure/plugin/GolemPluginManager.java` | Управление плагинами (PF4J) |
| `infrastructure/plugin/PluginBridge.java` | Мост: объединение встроенных и плагинных компонентов |
| `infrastructure/plugin/PluginRegistryService.java` | Кеширование и проверка реестра маркетплейса |
| `infrastructure/plugin/PluginContext.java` | Контекст для плагинов (ограниченный доступ к ядру) |
| `adapter/inbound/web/controller/PluginController.java` | REST API управления плагинами |
| `domain/model/PluginInfo.java` | Модель информации о плагине |
| `domain/model/MarketplaceEntry.java` | Модель записи маркетплейса |

### Изменяемые файлы (golemcore-bot)

| Файл | Изменение |
|------|-----------|
| `pom.xml` | Переход на multi-module parent POM |
| `infrastructure/config/BotProperties.java` | + `PluginsProperties` |
| `application.properties` | + секция `bot.plugins.*` |
| `adapter/inbound/CommandRouter.java` | + команда `/plugins` |
| `messages_en.properties` | + ключи `plugins.*` |
| `messages_ru.properties` | + ключи `plugins.*` |

### Новые файлы (golemcore-plugins)

| Файл | Описание |
|------|----------|
| `pom.xml` | Parent POM для плагинов |
| `brave-search-plugin/pom.xml` | POM плагина Brave Search |
| `brave-search-plugin/src/.../BraveSearchPlugin.java` | Plugin class |
| `brave-search-plugin/src/.../BraveSearchTool.java` | Extension: ToolComponent |
| `telegram-plugin/pom.xml` | POM плагина Telegram |
| `telegram-plugin/src/.../TelegramPlugin.java` | Plugin class |
| `telegram-plugin/src/.../TelegramChannelAdapter.java` | Extension: ChannelPort |
| ... (аналогично для каждого плагина) | |
| `plugin-registry.json` | Реестр маркетплейса (gh-pages) |
| `.github/workflows/build-plugins.yml` | CI: сборка плагинов |
| `.github/workflows/update-registry.yml` | CI: обновление реестра |

---

## См. также

- [Configuration](CONFIGURATION.md) — self-update и остальные runtime/Spring настройки
- [Deployment Guide](DEPLOYMENT.md) — текущее руководство по деплою
- [Tools](TOOLS.md) — документация по инструментам
