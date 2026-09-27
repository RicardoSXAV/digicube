# Build and run

Read this when a task needs more than `./gradlew build`: the client or the dedicated server, the first build,
refreshing dependencies, dev-run folders and crash logs.

Run from the repository root. On Windows use `gradlew.bat`; the examples below use the
POSIX form.

On macOS and Linux, run Gradle as the current user, without `sudo`. If `./gradlew`
cannot be executed, restore its executable permission with `chmod +x gradlew`.
The project compiles with Java 25. Gradle downloads that toolchain automatically
through the resolver in `settings.gradle` if it is not installed.

```bash
./gradlew build
```

```bash
./gradlew :fabric:runClient
```

```bash
./gradlew :fabric:runServer
```

```bash
./gradlew clean
```

```bash
./gradlew --refresh-dependencies
```

`build` compiles and assembles the jars and is the gate before calling anything done.
`runClient` and `runServer` launch Minecraft with the mod already loaded.
`--refresh-dependencies` is needed after changing versions in `gradle.properties`.

The first `build` downloads and decompiles Minecraft and takes **10–30 minutes**.
Later builds take seconds. Shipped jars land in `fabric/build/libs/`; ignore the
`-sources` and `-javadoc` ones.

Dev-run game files (worlds, logs, configs) live in `fabric/runs/client/` and
`fabric/runs/server/` and are git-ignored. Crash logs are at `runs/*/logs/latest.log` —
**read the actual stack trace before theorising about a cause.**
