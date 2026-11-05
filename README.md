# Executed

**Executed** is an HTTP request execution tool that supports `.http` files, similar to the HTTP Client built into IntelliJ IDEA.

## Disclaimer

This project is an experiment in building a tool entirely using AI. Use of this software is at your own discretion and risk. Proceed with caution when inspecting the source—reading this code without protective eyewear may cause temporary or permanent blindness.

## Running

`Executed.jar` is a single runnable jar for Linux (x64), Windows (x64) and macOS (Apple Silicon). It requires Java 25 or newer:

```sh
java -jar Executed.jar
```

Build it yourself with `./gradlew jar` (output: `build/libs/Executed.jar`).

## License

Executed is licensed under the [MIT License](LICENSE).

The jar bundles third-party libraries (including OpenJFX under GPLv2 with the Classpath Exception). See [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) and the [third-party-licenses](third-party-licenses) directory.
