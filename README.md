<p align="center">
  <img src="assets/brand.svg" alt="Shield" width="294" height="80">
</p>

<p align="center">
  <strong>Player behavior collection for Paper, Folia and Purpur</strong>
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

Shield targets the Paper 1.21.7 API and Java 21 bytecode. The same JAR runs on Paper, Folia and Purpur. Other forks must preserve the required Paper APIs and region scheduling semantics. Your Java version must also meet the server's own requirements.

| Server | Minecraft | Java | ProtocolLib |
| --- | --- | --- | --- |
| Paper | 1.21.7 | 21 | 5.4.0 |
| Folia | 1.21.8 | 21 | 5.4.0 |
| Paper | 26.3 | 25 | 5.5.0-SNAPSHOT-d89a5de (Paper build) |
| Folia | 26.3 | 25 | 5.5.0-SNAPSHOT-d89a5de (Paper build) |
| Purpur | 26.3 | 25 | 5.5.0-SNAPSHOT-d89a5de (Paper build) |

These combinations have passed checks for plugin loading, capture initialization, configuration reload and clean shutdown. Those checks do not cover live player movement across regions, combat, warning acknowledgement or the production inference API. Other Minecraft versions and server forks have not been individually verified.

The ProtocolLib Paper development build listed above requires Minecraft 26.2 or later and cannot be used on older servers. Native Spigot/Bukkit, Fabric, Forge and Velocity/BungeeCord proxies are outside the supported scope.

## Configuration and commands

See [`config.yml`](src/main/resources/config.yml) for the full configuration. Setting only `server-key` uses the default intervention policy: warnings enabled, kicks disabled. Even when kicks are enabled, each kick requires a directive authorized by the remote service and must pass local warning count, cooldown and expiry checks.

| Command | Purpose |
| --- | --- |
| `/shield` | Show collection, delivery and queue status |
| `/shield reload` | Validate configuration and reload the intervention policy |

Both commands work from the console. Players need `shield.admin`, which defaults to OP.

<details>
<summary>Intervention policy and validation</summary>

These settings belong under `interventions`.

| Setting | Default | Description |
| --- | --- | --- |
| `enabled` | `true` | Enable interventions |
| `warning.enabled` | `true` | Allow warnings |
| `warning.cooldown-seconds` | `1800` | Warning cooldown, 300–86400 seconds |
| `warning.message` | `行为异常` | Warning text shown to the player |
| `kick.enabled` | `false` | Allow kicks |
| `kick.minimum-prior-warnings` | `1` | Required acknowledged warnings, 1–100 |
| `kick.cooldown-seconds` | `3600` | Kick cooldown, 60–604800 seconds |
| `kick.message` | `异常行为持续发生` | Kick reason shown to the player |

Both messages must be a single line of 1–256 characters. Unknown settings, incorrect types and out-of-range values prevent startup. Invalid configuration during reload leaves the current policy in effect.

Warnings are acknowledged when the player closes the dialog. A kick receipt confirms that the kick was invoked, not that the client disconnected. Outcomes are persisted, retried and reported through the event queue, including disabled actions, unmet prerequisites, cooldown limits, offline players, expired directives and invalid results.

</details>

<details>
<summary>Reload behavior and preserved state</summary>

`/shield reload` reads and validates the entire configuration before replacing the intervention policy. Missing files, invalid YAML or invalid settings leave the current policy in effect.

Actions that have not started are checked against the new policy. Warning dialogs already shown continue their existing acknowledgement and receipt flow. Reload preserves queued events, cooldowns, warning counts and receipts without repeating past directives.

Changing `server-key` requires a server restart because the key identifies the server and its delivery queue. If a changed key is detected during reload, the entire reload is rejected, including changes to the intervention policy.

</details>

## Data and service dependency

The plugin sends player UUIDs, names, worlds, game modes, positions, behavior events, collection status and intervention receipts to the Shield service over HTTPS. It does not collect chat content or custom payloads. Event fields are defined in [`PacketSample`](src/main/kotlin/com/eyreland/shield/capture/PacketSample.kt).

When delivery fails, events are queued locally and retried. The pending queue holds up to 4096 batches; the disk cache is limited to 256 MiB and a retention period of 6 hours. Data exceeding capacity or retention limits is removed. Run `/shield` to inspect queue status and drop counts.

## Building from source

Use JDK 25 and the included Gradle Wrapper. Runtime requirements are listed under [Compatibility](#compatibility).

```bash
./gradlew build
```

On Windows, run `.\gradlew.bat build`. The output is `build/libs/shield-<version>.jar`. Set `-Pversion=<version>` to choose the version number. Dependencies and packaging are declared in [`build.gradle.kts`](build.gradle.kts).

## Contributing

For bug reports, include your server software and Minecraft version, Java version, ProtocolLib version, Shield version, reproduction steps and relevant logs. Remove server keys and player personal information before sharing logs.

Fixes and compatibility improvements are welcome. English is recommended for issues and pull requests; other languages are welcome. Keep each PR focused on one problem and use Conventional Commit prefixes such as `fix:`, `feat:` or `docs:` for commit and PR titles.

## License

Copyright (C) 2026 PluginsKers.

Plugin source is licensed under [AGPL-3.0-only](LICENSE), with an additional linking permission for Minecraft servers and ProtocolLib. See [NOTICE](NOTICE) for that permission and the third-party notices included in the JAR.
