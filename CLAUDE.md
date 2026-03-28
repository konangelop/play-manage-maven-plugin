# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
mvn clean install          # Full build (compile + package plugin JAR)
mvn compile                # Compile plugin source only
mvn clean install -DskipTests  # Same effect (no tests exist yet)
```

This is a Maven plugin project (`<packaging>maven-plugin</packaging>`). The `maven-plugin-plugin` processes `@Mojo` annotations to generate `META-INF/maven/plugin.xml`.

## What This Is

A Maven plugin (`io.github.konangelop:play-manage-maven-plugin`) that replaces SBT for Java-based Play Framework 2.9.x projects. It provides the full build lifecycle: routes compilation, Twirl template compilation, mixed Java+Scala compilation via Zinc, bytecode enhancement, dev-mode hot reload, production server management, and distribution packaging.

This plugin replaced a previous 6-module `play-maven-plugin` that had 9 Mojos + 9 abstract base classes and a provider SPI abstraction layer. The rewrite intentionally flattened everything into a single module with flat Mojo classes that call Play 2.9.x APIs directly — no abstraction layers, no SPI, no multi-module complexity. Preserve this simplicity.

## Architecture

All source lives in `src/main/java/io/github/konangelop/play/maven/`. Each Mojo maps to a Maven goal:

**Code generation phase** (`generate-sources`):
- `RoutesCompileMojo` (`routes-compile`) — Calls Play's `RoutesCompiler$` Scala API directly via Java-Scala interop (`CollectionConverters`). Scans `conf/` for `routes` and `*.routes` files.
- `TemplateCompileMojo` (`template-compile`) — Calls `TwirlCompiler.compile()` Scala API. Scans `app/` for `.scala.html/.txt/.xml/.js` files.

**Compilation phase** (`compile` / `test-compile`):
- `CompileMojo` (`compile`) and `TestCompileMojo` (`test-compile`) — Thin Mojos that delegate to `ZincCompilerSupport`, a package-private utility encapsulating the full Zinc incremental compiler pipeline (ScalaInstance, AnalysisStore, Reporter, Logger). CompileMojo uses main source roots/classpath; TestCompileMojo uses test roots/classpath and respects `maven.test.skip`.

**Post-compilation phase** (`process-classes`):
- `EnhanceClassesMojo` (`enhance-classes`) — Javassist-based; generates getter/setter methods for non-public, non-static, non-final fields.
- `EbeanEnhanceMojo` (`ebean-enhance`) — Loads `io.ebean.enhance.Transformer` **reflectively** from the consumer project's classpath (not a plugin dependency). Requires `io.ebean:ebean-agent` in the consumer project.

**Dev mode** (no lifecycle phase):
- `RunMojo` (`run`) — Starts Play dev server with hot reload. Uses `MavenBuildLink` (implements Play's `BuildLink` interface) + `WatchService` for file change detection + Maven Invoker for incremental rebuilds. Loads `DevServerStart$` reflectively from the project's runtime classpath.
- `MavenBuildLink` — Core of dev mode. On each HTTP request, Play calls `reload()` which polls the WatchService, triggers Maven rebuild if sources changed, and returns a fresh `URLClassLoader`.

**Production mode**:
- `StartMojo` (`start`) — Spawns background JVM process, writes PID file, performs HTTP health check.
- `StopMojo` (`stop`) — Reads PID file, sends `destroy()` then `destroyForcibly()` with timeout.

**Packaging** (`package`):
- `DistMojo` (`dist`) — Creates zip archive with `lib/`, `bin/` (generated start scripts), `conf/`. Uses Plexus `ZipArchiver`.

## Key Design Patterns

- **Scala interop from Java**: Routes and template mojos call Scala APIs directly. Conversions use `scala.jdk.javaapi.CollectionConverters` and Scala `Option`/`Either`/`Seq` types.
- **Reflective loading**: RunMojo and EbeanEnhanceMojo load classes from the consumer project's classpath at runtime, not from plugin dependencies. This avoids version conflicts.
- **Maven Invoker for rebuilds**: Dev mode triggers `mvn generate-sources process-classes` (configurable via `play.runGoals`) as a subprocess for each hot-reload cycle.

## Version Coordinates

- Java 17 source/target
- Scala 2.13.17, Play 2.9.6, Twirl 1.6.8, Zinc 1.12.0
- Maven API 3.9.6

## Stale Branch

`origin/claude/scala-incremental-compiler-plugin-uSNBD` is a leftover worktree branch from the original session — it's behind master and can be deleted.
