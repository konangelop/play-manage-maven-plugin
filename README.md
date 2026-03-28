# Play Manage Maven Plugin

A Maven plugin for building and running **Java-based Play Framework 2.9.x** projects without SBT.

Provides 10 goals covering the full lifecycle: routes compilation, Twirl template compilation, mixed Java+Scala compilation (Zinc), bytecode enhancement, dev-mode hot reload, production server management, and distribution packaging.

## Requirements

- Java 11+
- Maven 3.9+
- Play Framework 2.9.x

## Quick Start

Add the plugin to your Play project's `pom.xml`:

```xml
<plugin>
    <groupId>io.github.konangelop</groupId>
    <artifactId>play-manage-maven-plugin</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <executions>
        <execution>
            <goals>
                <goal>routes-compile</goal>
                <goal>template-compile</goal>
                <goal>compile</goal>
                <goal>test-compile</goal>
                <goal>enhance-classes</goal>
                <goal>dist</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

Then:

```bash
mvn compile              # Compile routes, templates, and Java+Scala sources
mvn package              # Compile + create distribution zip
mvn play-manage:run      # Start dev server with hot reload on port 9000
mvn play-manage:start    # Start production server in background
mvn play-manage:stop     # Stop production server
```

## Goals

### `routes-compile`

Compiles Play routes files (`conf/routes`, `conf/*.routes`) into Scala router source code.

**Phase:** `generate-sources`

| Property | Default | Description |
|---|---|---|
| `play.routesDirectory` | `${basedir}/conf` | Directory containing routes files |
| `play.routesOutputDirectory` | `${project.build.directory}/generated-sources/play-routes` | Output directory for generated sources |
| `play.routesAdditionalImports` | | Additional imports for generated routes |
| `play.forwardsRouter` | `true` | Generate the forwards router |
| `play.reverseRouter` | `true` | Generate the reverse router |
| `play.namespaceReverseRouter` | `false` | Namespace the reverse router |
| `play.routes.skip` | `false` | Skip routes compilation |

### `template-compile`

Compiles Twirl template files (`.scala.html`, `.scala.txt`, `.scala.xml`, `.scala.js`) into Scala source code.

**Phase:** `generate-sources`

| Property | Default | Description |
|---|---|---|
| `play.templateSourceDirectory` | `${basedir}/app` | Root directory containing templates |
| `play.templateOutputDirectory` | `${project.build.directory}/generated-sources/twirl` | Output directory for generated sources |
| `play.templateAdditionalImports` | | Additional imports for generated templates |
| `play.templateConstructorAnnotations` | | Constructor annotations (e.g. `@javax.inject.Inject()`) |
| `play.templateEncoding` | `UTF-8` | Source file encoding |
| `play.templates.skip` | `false` | Skip template compilation |

### `compile`

Compiles mixed Java and Scala source code using the Zinc incremental compiler. Only changed files are recompiled thanks to Zinc's analysis cache.

**Phase:** `compile`

| Property | Default | Description |
|---|---|---|
| `play.compile.outputDirectory` | `${project.build.outputDirectory}` | Output directory for compiled classes |
| `play.compile.analysisCache` | `${project.build.directory}/zinc-analysis` | Zinc analysis cache file |
| `play.compile.scalacOptions` | | Additional scalac compiler options |
| `play.compile.javacOptions` | | Additional javac compiler options |
| `play.compile.skip` | `false` | Skip compilation |

### `test-compile`

Compiles mixed Java and Scala test source code using the Zinc incremental compiler. Test counterpart of the `compile` goal — compiles test sources against main classes and test-scoped dependencies.

**Phase:** `test-compile`

| Property | Default | Description |
|---|---|---|
| `play.testCompile.outputDirectory` | `${project.build.testOutputDirectory}` | Output directory for compiled test classes |
| `play.testCompile.analysisCache` | `${project.build.directory}/zinc-test-analysis` | Zinc analysis cache file for tests |
| `play.testCompile.scalacOptions` | | Additional scalac compiler options |
| `play.testCompile.javacOptions` | | Additional javac compiler options |
| `play.testCompile.skip` | `false` | Skip test compilation |
| `maven.test.skip` | `false` | Skip test compilation (standard Maven property) |

### `enhance-classes`

Generates public getter/setter methods for non-public fields in compiled classes via Javassist bytecode enhancement.

**Phase:** `process-classes`

| Property | Default | Description |
|---|---|---|
| `play.classesDirectory` | `${project.build.outputDirectory}` | Directory containing classes to enhance |
| `play.enhance.skip` | `false` | Skip enhancement |

### `ebean-enhance`

Enhances Ebean entity classes with bytecode for lazy loading and dirty checking. Requires `io.ebean:ebean-agent` as a dependency in your project.

**Phase:** `process-classes`

| Property | Default | Description |
|---|---|---|
| `play.classesDirectory` | `${project.build.outputDirectory}` | Directory containing classes to enhance |
| `play.ebeanModels` | | Package patterns to enhance (e.g. `models.*,com.example.models.*`) |
| `play.ebean.skip` | `false` | Skip Ebean enhancement |

### `run`

Starts the Play application in development mode with hot reload. On each HTTP request, Play checks for source changes, triggers an incremental rebuild if needed, and swaps the application classloader without restarting the JVM.

Your project must depend on a Play server backend (e.g. `play-akka-http-server`).

**Phase:** none (invoke directly)

| Property | Default | Description |
|---|---|---|
| `play.httpPort` | `9000` | HTTP port |
| `play.httpsPort` | `-1` | HTTPS port (`-1` to disable) |
| `play.httpAddress` | `0.0.0.0` | Bind address |
| `play.devSettings` | | Dev settings as `key1=value1,key2=value2` |
| `play.watchDirectories` | `app`, `conf`, `public` | Directories to watch for changes |
| `play.runGoals` | `generate-sources compile process-classes` | Maven goals to run on rebuild |
| `play.run.skip` | `false` | Skip the run goal |

### `start`

Starts the Play application in production mode as a background process. Writes a PID file and performs a health check.

**Phase:** none (invoke directly)

| Property | Default | Description |
|---|---|---|
| `play.httpPort` | `9000` | HTTP port |
| `play.httpsPort` | `-1` | HTTPS port (`-1` to disable) |
| `play.httpAddress` | `0.0.0.0` | Bind address |
| `play.mainClass` | `play.core.server.ProdServerStart` | Main class |
| `play.serverJvmArgs` | | Additional JVM arguments (list) |
| `play.prodSettings` | | System properties as list (e.g. `-Dkey=value`) |
| `play.startTimeout` | `30` | Seconds to wait for server readiness |
| `play.startCheckUrl` | `/` | URL path to poll for health check (empty to skip) |
| `play.pidFile` | `${project.build.directory}/play.pid` | PID file location |
| `play.logFile` | `${project.build.directory}/play.log` | Log file location |
| `play.start.skip` | `false` | Skip starting |

### `stop`

Stops a Play application that was started with the `start` goal. Sends a graceful termination signal, then force-kills after the timeout.

**Phase:** none (invoke directly)

| Property | Default | Description |
|---|---|---|
| `play.pidFile` | `${project.build.directory}/play.pid` | PID file location |
| `play.stopTimeout` | `10` | Seconds to wait before force-killing |
| `play.stop.skip` | `false` | Skip stopping |

### `dist`

Creates a standalone distribution zip archive containing `lib/` (JARs), `bin/` (start scripts for Unix and Windows), and `conf/` (configuration).

**Phase:** `package`

| Property | Default | Description |
|---|---|---|
| `play.distOutputDirectory` | `${project.build.directory}` | Output directory for the archive |
| `play.distArchiveName` | `${project.build.finalName}` | Base name of the archive |
| `play.distTopLevelDirectory` | `${project.build.finalName}` | Top-level directory inside the zip |
| `play.distMainClass` | `play.core.server.ProdServerStart` | Main class for start scripts |
| `play.distJvmArgs` | | JVM arguments for start scripts |
| `play.distProdSettings` | | System properties for start scripts |
| `play.distClassifier` | `dist` | Maven artifact classifier |
| `play.distAttach` | `true` | Attach as a Maven artifact |
| `play.distConfDirectory` | `${basedir}/conf` | Configuration directory to include |
| `play.dist.skip` | `false` | Skip distribution creation |

## Building the Plugin

```bash
mvn clean install
```

## License

TBD
