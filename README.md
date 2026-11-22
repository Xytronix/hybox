# Hybox

Investigate lag, freezes and crashes on Hytale servers. Hybox keeps a rolling Java Flight Recorder (JFR) recording and saves incident bundles with an HTML report, JFR data and thread dumps. Plugins can add their own diagnostics and metrics through a small API.

Build with Java 25 (`./gradlew clean build`), then copy `hybox-hytale/build/libs/Hybox-1.0.0.jar` into the server's `mods/` folder and restart. Run `/hybox` in game to open the dashboard.

Read the [documentation](docs/README.md) for installation, configuration, triggers, privacy and the plugin API.

[MIT license](LICENSE). Fork of [Blackbox](https://github.com/ZECHEESELORD/blackbox).
