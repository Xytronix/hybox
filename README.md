# Hybox

Investigate lag, freezes and crashes on Hytale servers. Hybox keeps a rolling Java Flight Recorder (JFR) recording and saves incident bundles with an HTML report, JFR data and thread dumps. Plugins can add their own diagnostics and metrics through a small API.

Download `Hybox-X.X.X.jar` from the [releases](https://github.com/Xytronix/hybox/releases), put it in the server's `mods` folder and restart. Run `/hybox` in game to open the dashboard. To build from source, run `./gradlew clean build` with Java 25.

Read the [documentation](https://wiki.hytalemodding.dev/mod/hybox) for installation, configuration, triggers, privacy and the plugin API.

[MIT license](LICENSE). Fork of [Blackbox](https://github.com/ZECHEESELORD/blackbox).
