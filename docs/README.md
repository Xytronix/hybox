# Hybox

Hybox records what a Hytale server was doing when it lagged, froze or crashed. It keeps a rolling Java Flight Recorder (JFR) recording, watches the server for trouble and saves an incident bundle when it finds some.

## Server owners

- [Installing Hybox](installing.md): requirements, build, data folder and permissions.
- [Using Hybox](using.md): commands, the dashboard, incidents, profiling and sharing a bundle.
- [Triggers](triggers.md): what each detector measures and when it saves a bundle.
- [Privacy](privacy.md): what a bundle contains and what Hybox hides.
- [Troubleshooting](troubleshooting.md): what Hybox's messages mean and how to fix them.

## Plugin developers

- [Plugin API](plugin-api.md): add diagnostics, metrics, events and files to bundles.

## Reference

- [`hybox.json`](configuration.md): every setting, its default and when it applies.
