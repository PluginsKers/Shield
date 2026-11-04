<p align="center">
  <img src="assets/brand.svg" alt="Shield" width="294" height="80">
</p>

<p align="center">
  <strong>Player behavior collection for Paper, Folia, Canvas and Purpur</strong>
</p>

<p align="center">
  <a href="https://github.com/PluginsKers/Shield/releases">Download</a>&nbsp;&nbsp;
  <a href="#getting-started">Install</a>&nbsp;&nbsp;
  <a href="https://shield.i5res.com">Website</a>&nbsp;&nbsp;
  <a href="https://github.com/PluginsKers/Shield/issues">Report an issue</a>
</p>

<p align="center">
  English&nbsp;&nbsp;<a href="README.zh-CN.md">简体中文</a>
</p>

Shield collects player movement, combat and interaction events and sends them asynchronously to the Shield service for analysis and human review. The plugin carries out authorized warnings or kicks. It does not ban players.

To use Shield, register a server in the [dashboard](https://shield.i5res.com/dashboard#servers) and obtain a server key. Behavior analysis requires the remote Shield service; the plugin does not provide offline inference.

## Getting started

1. Check the [compatibility table](#compatibility) below and install a ProtocolLib build that supports your Minecraft version.
2. Download `shield-<version>.jar` from [Releases](https://github.com/PluginsKers/Shield/releases) and place it in your server's `plugins` directory.
3. Register your server in the Shield dashboard and save its connection configuration to `plugins/shield/config.yml`. Create the directory if needed. The minimal configuration is:

   ```yaml
   server-key: "paste-your-server-key"
   ```

4. Restart the server and run `/shield` to check collection and delivery status.

Before upgrading, back up `plugins/shield`. Replace the JAR and restart the server. Preserve the event queue and intervention records in that directory so pending data and action state are retained.

## Compatibility

Shield targets the Paper 1.21.7 API and Java 21 bytecode. Paper, Folia, Canvas and Purpur use the same JAR. Other forks must preserve the required Paper APIs and region scheduling semantics. Your Java version must also meet the server's own requirements.

The current compatibility matrix uses ProtocolLib 5.5.0-SNAPSHOT from [Jenkins build 769](https://ci.dmulloy2.net/job/ProtocolLib/769/):

| Minecraft | Java | Folia build | Canvas build | ProtocolLib artifact |
| --- | --- | --- | --- | --- |
| 1.21.11 | 21 | 14 | 794 | `ProtocolLib-Spigot.jar` |
| 26.1.2 | 25 | 8 | 876 | `ProtocolLib-Spigot.jar` |
| 26.2 | 25 | 7 | 994 | `ProtocolLib.jar` (Paper) |
| 26.3 | 25 | 6 | 1001 | `ProtocolLib.jar` (Paper) |

All eight combinations have passed plugin loading, capture initialization, status and control commands, configuration reload and clean shutdown. Folia and Canvas 26.3 also cover player login, heartbeats, cross-region teleport and chat warning delivery with receipts against a local service fixture. These checks do not verify combat detection accuracy or the production inference service.

The ProtocolLib Paper artifact requires Minecraft 26.2 or later. Use its Spigot artifact on the older versions listed above, including Folia and Canvas. Native Spigot/Bukkit, Fabric, Forge and Velocity/BungeeCord proxies are outside the supported scope.

## Configuration and commands

See [`config.yml`](src/main/resources/config.yml) for the full configuration. Setting only `server-key` uses the default intervention policy: warnings enabled, kicks disabled. The Shield service issues a warning after `warning.windows` consecutive review windows and a kick after `kick.windows` consecutive priority windows; each window covers 8 seconds of rule-detector evidence. The plugin reports both counts with every batch and only executes enabled, unexpired directives.

| Command | Purpose |
| --- | --- |
| `/shield` | Show overall control, interventions, collection, delivery and queue status |
| `/shield enable` | Enable Shield |
| `/shield disable` | Stop collection, delivery and new interventions |
| `/shield interventions enable` | Allow interventions while preserving the individual warning and kick settings |
| `/shield interventions disable` | Disable interventions while collection and delivery continue |
| `/shield reload` | Validate configuration and reload controls and intervention policy |

All commands work from the console. Players need `shield.admin`, which defaults to OP.

The root `enabled` setting controls Shield and defaults to `true`; `interventions.enabled` controls interventions. Control commands validate and save `config.yml` before applying changes. Enabling interventions while Shield is disabled takes effect when Shield is enabled.

Disabling Shield stops collection, new uploads and new interventions. Enabling it resumes delivery and starts new collection sessions for online players.

The following limits apply under `interventions`; defaults are in [`config.yml`](src/main/resources/config.yml).

| Setting | Limit |
| --- | --- |
| `warning.windows`, `kick.windows` | Consecutive 8-second windows, 1–100 |
| `warning.message`, `kick.message` | A single line of 1–256 characters |

Unknown settings, incorrect types and out-of-range values prevent startup. Reload validates the entire configuration before applying controls and policy; missing files or invalid configuration leave the current settings in effect. Actions that have not started use the new policy.

Disabling or reloading preserves queued events and receipts without repeating past directives. Queue capacity and retention limits still apply. In-flight requests complete their receipt flow. A warning is a red chat message and its receipt confirms the message was sent; a kick receipt confirms invocation, not client disconnection. Action outcomes are persisted, retried and reported through the event queue.

Changing `server-key` or `endpoint` requires a server restart. Until then, reload and control commands reject the change and retain the current configuration.

## Data and service dependency

The plugin sends player UUIDs, names, worlds, game modes, positions, behavior events, collection status and intervention receipts to the Shield service at `endpoint`, which defaults to the public HTTPS API. It does not collect chat content or custom payloads. Event fields are defined in [`PacketSample`](src/main/kotlin/com/eyreland/shield/capture/PacketSample.kt).

When delivery fails, events are queued locally and retried. The pending queue holds up to 4096 batches; the disk cache is limited to 256 MiB and a retention period of 6 hours. Data exceeding capacity or retention limits is removed. Run `/shield` to inspect queue status and drop counts.

## Building from source

Use JDK 25 and the included Gradle Wrapper. Runtime requirements are listed under [Compatibility](#compatibility).

```bash
./gradlew build
```

On Windows, run `.\gradlew.bat build`. The output is `build/libs/shield-<version>.jar`. Set `-Pversion=<version>` to choose the version number. Dependencies and packaging are declared in [`build.gradle.kts`](build.gradle.kts).

[GitHub Actions](https://github.com/PluginsKers/Shield/actions/workflows/build.yml) builds pull requests, updates to `main` and manual runs, and uploads the JAR with `SHA256SUMS`. Pushing a `vX.Y.Z` tag builds that version and publishes both files in a GitHub Release. All supported server versions share this artifact.

## Contributing

For bug reports, include your server software and Minecraft version, Java version, ProtocolLib version, Shield version, reproduction steps and relevant logs. Remove server keys and player personal information before sharing logs.

Fixes and compatibility improvements are welcome. English is recommended for issues and pull requests; other languages are welcome. Keep each PR focused on one problem and use Conventional Commit prefixes such as `fix:`, `feat:` or `docs:` for commit and PR titles.

## License

Copyright (C) 2026 PluginsKers.

Plugin source is licensed under [AGPL-3.0-only](LICENSE), with an additional linking permission for Minecraft servers and ProtocolLib. See [NOTICE](NOTICE) for that permission and the third-party notices included in the JAR.
