

# GolemCore Bot

> Plataforma de agentes para empresas nativas de IA: crea, ejecuta, extiende e inspecciona agentes conectados a canales con habilidades, complementos, MCP, memoria y Hive.

[![CI](https://github.com/alexk-dev/golemcore-bot/actions/workflows/docker-publish.yml/badge.svg)](https://github.com/alexk-dev/golemcore-bot/actions/workflows/docker-publish.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Tests](https://img.shields.io/badge/tests-passing-success.svg)](https://github.com/alexk-dev/golemcore-bot/actions)

---

## Qué es

GolemCore Bot es la plataforma de agentes para empresas nativas de IA que construyen sobre el ecosistema GolemCore.

Úsala para ejecutar agentes conectados a canales, extenderlos con habilidades, complementos y servidores MCP, y operarlos con memoria, seguimientos diferidos, inspección de trazas y flujos de control conectados a Hive.

Utilízala de dos maneras:

1. **Ejecutar agentes** — chatea en el panel, Telegram o flujos impulsados por webhooks, y luego inspecciona la ejecución, la memoria y la salida de trazas.
2. **Extender el entorno de ejecución** — agrega habilidades, instala complementos, conecta servidores MCP, configura niveles de enrutamiento y adjunta control e inspección basados en Hive.

## Por qué es diferente

- **Entorno de ejecución nativo para complementos** — los complementos pueden contribuir con herramientas, canales, proveedores de voz y ingestión RAG en lugar de vivir fuera del entorno de ejecución del agente.
- **Listo para MCP por diseño** — las habilidades pueden iniciar servidores MCP, y el entorno de ejecución ahora incluye flujos de catálogo y descubrimiento MCP.
- **Diseñado para trabajos de larga duración** — los seguimientos diferidos, la orquestación progresiva de la memoria y las rutas de recuperación ayudan a los agentes a continuar más allá de una sola interacción.
- **Superficie de control conectada a Hive** — conecta los agentes a Hive para comandos de control, señales del ciclo de vida, inspección y coordinación a nivel de host.
- **Ejecución inspeccionable** — las trazas, las vistas en cascada y las instantáneas exportadas hacen que el comportamiento de los agentes sea más fácil de depurar y operar.

---

## Inicio rápido

### Lo que necesitas

- Docker
- Al menos una clave API de un proveedor de LLM

### Ejecutar la imagen publicada

```bash
docker pull ghcr.io/alexk-dev/golemcore-bot:latest

docker run -d \
  --name golemcore-bot \
  --shm-size=256m \
  --cap-add=SYS_ADMIN \
  -e STORAGE_PATH=/app/workspace \
  -e TOOLS_WORKSPACE=/app/sandbox \
  -v golemcore-bot-data:/app/workspace \
  -v golemcore-bot-sandbox:/app/sandbox \
  -p 8080:8080 \
  --restart unless-stopped \
  ghcr.io/alexk-dev/golemcore-bot:latest

docker logs -f golemcore-bot

# Health probe:
# curl http://localhost:8080/api/system/health
#
# Compatibility: Docker CMD overrides may still pass Spring Boot args directly:
# docker run ... ghcr.io/alexk-dev/golemcore-bot:latest --server.port=9090

# Open http://localhost:8080/dashboard
# On first start, check logs for the temporary admin password.
```

Abre `http://localhost:8080/dashboard`, inicia sesión con la contraseña de administrador temporal de los registros y luego configura tus proveedores de LLM en Configuración.

¿Por qué las banderas adicionales de Docker?
- `--shm-size=256m` y `--cap-add=SYS_ADMIN` son necesarios para la **herramienta Browser** (Playwright/Chromium) en los contenedores.
  Consulta **[Configuración → Herramienta Browser](docs/CONFIGURATION.md#browser-tool)**.

### Pasos siguientes opcionales

- Habilita Telegram en Configuración para el chat basado en canales.
- Instala complementos para navegador, búsqueda, correo, clima, voz o flujos de trabajo respaldados por RAG.
- Conecta habilidades y servidores de herramientas respaldados por MCP.
- Únete a Hive para la coordinación e inspección del plano de control.

¿Necesitas una compilación local, configuración con Compose o ruta de implementación en producción? Consulta **[Inicio rápido](docs/QUICKSTART.md)** y **[Implementación](docs/DEPLOYMENT.md)**.

---

## Paquete local nativo de app-image (experimental)

Además de Docker y el JAR ejecutable sencillo, el flujo de trabajo de lanzamiento ahora también publica un **paquete local de app-image** para el sistema operativo/arquitectura actual.

### Compilarlo localmente

```bash
./mvnw clean package -DskipTests -DskipGitHooks=true
npx golemcore-bot-local-build-native-dist
```

Esto genera un archivo en:

```text
target/native-dist/golemcore-bot-<version>-<platform>-<arch>.tar.gz
```

### Qué contiene

La app-image contiene:

- una pequeña aplicación lanzador producida por `jpackage`
- un entorno de ejecución de Java empaquetado, por lo que ejecutar la app-image extraída no requiere una instalación de Java por separado
- el jar del entorno de ejecución actualizable por sí mismo habitual bajo `lib/runtime/`
- un punto de entrada nativo para lanzador impulsado por picocli con ayuda incorporada y opciones exclusivas del lanzador
- configuración del lanzador que apunta primero a ese jar del entorno de ejecución empaquetado

Por lo tanto, el orden de inicio se convierte en:

1. actualización escalonada desde `updates/current.txt`, a menos que el jar del entorno de ejecución empaquetado sea más reciente
2. jar del entorno de ejecución empaquetado desde la app-image
3. alternativa legacy de Jib/classpath

### Opciones del lanzador nativo

El lanzador nativo utiliza picocli, por lo que tiene ayuda de primera clase y un pequeño conjunto de banderas específicas del lanzador.

Mostrar ayuda:

```bash
./golemcore-bot/bin/golemcore-bot --help
```

Opciones comunes:

- `web` — comando obligatorio que inicia el entorno de ejecución de Spring Boot empaquetado
- `web --port=<port>` — reenvía `-Dserver.port=<port>` al entorno de ejecución generado
- `web --hostname=<address>` — reenvía `-Dserver.address=<address>` al entorno de ejecución generado
- `--storage-path=<path>` / `web --storage-path=<path>` — reenvía `-Dbot.storage.local.base-path=<path>`
- `--updates-path=<path>` / `web --updates-path=<path>` — reenvía `-Dbot.update.updates-path=<path>`
- `--bundled-jar=<path>` — sobrescribe la ruta del jar del entorno de ejecución empaquetado
- `web -J=<jvm-option>` / `web --java-option=<jvm-option>` — reenvía opciones adicionales de JVM al entorno de ejecución generado

El paquete nativo inicia el entorno de ejecución de Spring con el perfil `prod` de forma predeterminada.

Ejemplos:

```bash
./golemcore-bot/bin/golemcore-bot web --port=8080 --hostname=0.0.0.0
./golemcore-bot/bin/golemcore-bot web -J=-Xmx1g --port=9090
./golemcore-bot/bin/golemcore-bot --storage-path=/srv/golemcore/workspace web --updates-path=/srv/golemcore/updates
```

### Los argumentos del entorno de ejecución de Spring siguen funcionando

Los argumentos desconocidos se reenvían a Spring Boot sin cambios, por lo que los argumentos de aplicación existentes continúan funcionando:

```bash
./golemcore-bot/bin/golemcore-bot web --server.port=9090
./golemcore-bot/bin/golemcore-bot web --spring.main.banner-mode=off
./golemcore-bot/bin/golemcore-bot web -Dlogging.level.root=INFO
```

Si deseas hacer la separación explícita, también puedes usar `--` antes de los argumentos de Spring:

```bash
./golemcore-bot/bin/golemcore-bot web --port=9090 -- --spring.main.banner-mode=off
```

### Por qué es importante

Esto mantiene el modelo de autoactualización existente basado en:

- `updates/current.txt`
- `updates/jars/`

al tiempo que permite que el bot inicie correctamente desde un paquete local nativo con parámetros de lanzador documentados.

---

## Configuración de la primera ejecución

1. Abre el panel.
2. Agrega al menos una clave de proveedor de LLM en Configuración.
3. Verifica que los volúmenes de almacenamiento y sandbox estén montados para que las sesiones, habilidades y configuración del entorno de ejecución persistan.
4. Opcionalmente, habilita Telegram, instala complementos, conecta habilidades respaldadas por MCP o únete a Hive.

Para detalles de configuración del entorno de ejecución, estructura de almacenamiento y notas sobre el navegador o el sandbox, consulta **[Configuración](docs/CONFIGURATION.md)**.

---

## Documentación

Sitio de documentación: https://docs.golemcore.me/

La documentación de origen a continuación se mantiene en este repositorio para referencia local y sin conexión:

1. **[Inicio rápido](docs/QUICKSTART.md)**
2. **[Habilidades](docs/SKILLS.md)**
3. **[Herramientas](docs/TOOLS.md)**
4. **[Integración MCP](docs/MCP.md)**
5. **[Enrutamiento de modelos](docs/MODEL_ROUTING.md)**
6. **[Memoria](docs/MEMORY.md)** + **[RAG](docs/RAG.md)**
7. **[Modo automático](docs/AUTO_MODE.md)** + **[Diseño de acciones diferidas](docs/DELAYED_ACTIONS.md)**
8. **[Integración con Hive](docs/HIVE_INTEGRATION.md)**
9. **[Webhooks](docs/WEBHOOKS.md)**
10. **[Implementación](docs/DEPLOYMENT.md)**
11. **[Panel](docs/DASHBOARD.md)**

---

## Contribución / Soporte / Licencia

- Contribución: consulta **[CONTRIBUTING.md](CONTRIBUTING.md)** (flujo de trabajo, controles de calidad, pruebas).
- FAQ: **[FAQ.md](FAQ.md)**.
- Problemas (Issues): https://github.com/alexk-dev/golemcore-bot/issues
- Licencia: **Apache 2.0** — consulta **[LICENSE](LICENSE)** y **[NOTICE](NOTICE)**.

---

<div align="center">

**⭐ ¡Hazle una estrella a este repositorio si te resultó útil!**

Hecho con ☕ y 🤖

</div>
