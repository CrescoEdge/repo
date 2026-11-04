# Cresco Repo

Plugin repository — stores, reports and deploys Cresco plugins.

Part of the **[Cresco](https://github.com/CrescoEdge/agent)** edge-computing framework. See the
**[agent repository](https://github.com/CrescoEdge/agent)** for the full architecture, build, and run guide.

## Role

A Cresco plugin that stores, reports on, and deploys other plugins within the fabric. Loaded by the controller.

## Actions and security

| Action | Roles | Purpose |
|---|---|---|
| `repolist` | default | The plugin jars here (name, content hash, file, version) and this repo's identity. |
| `getjar` | default | A jar's bytes by `action_pluginname` + `action_pluginmd5`; the bytes sent are rechecked against the hash. |
| `putjar` | **infra, admin** | Store a plugin jar (see below). |
| `getmetrics`, `getcapabilities` | default | Metrics, capability document. |

Roles are enforced by the agent before the repo runs (`@CrescoAction(roles=...)`; the controller's
`docs/caller-authorization.md`); "default" is any caller of this agent's tenant, and `infra`.

**putjar accepts only signed jars.** Other agents load these jars as bundles, so a jar lands only if every entry is
signed by a signer that chains to the agent's `plugin_signing_truststore` (the library's `JarSignatures`, through
`PluginBuilder.verifyJarSignature`); unsigned, partially signed, tampered and foreign-signed jars are refused
(`status 9`, `refusal=signature`), and with no trust store configured every jar is. The repo computes the jar's
content hash (`md5-confirm`) and SHA-384 (`sha384`) itself; an `md5` the caller supplies is advisory, and one that
differs is refused. `pluginname` must be the jar's `Bundle-SymbolicName`, and `jarfile` a plain file name. The jar is
staged under a name the inventory never lists and moved into place atomically, so a partial or refused jar is never
served. `plugin_signing_required=false` (an agent setting) lets unsigned jars through for development only, and the
repo logs a WARNING at start when it is set, or when no trust store is configured.

The inventory caches each jar's hash and manifest by size, mtime and file key, instead of re-hashing every jar on
every `repolist` and `getjar`.

Statuses: `10` done; `9` refused or not found; `8` bad name or params; `7` missing params; `6` error.

## Upgrade notes (1.3 hardening)

- `putjar` needs the `infra` or `admin` role (the global controller's plugin upload sends it as `infra`).
- `putjar` accepts only jars signed by a signer chaining to the agent's `plugin_signing_truststore`: configure the
  trust store on every agent that runs a repo **before upgrading**, and sign plugin jars. Without one every `putjar`
  is refused, and the repo logs a WARNING at start.
- `pluginname` must equal the jar's `Bundle-SymbolicName`; `md5` is optional (when sent it must match); every reply
  carries `status`, and `sha384` on success.
- Files in the repository directory without a `Bundle-SymbolicName` are no longer listed by `repolist`.

## Build

```bash
mvn package bundle:bundle
```

Built with **JDK 21**. The `bundle:bundle` goal is required: it rewrites the jar into a
proper OSGi bundle (with `Bundle-SymbolicName`, `Export-Package`, and embedded
dependencies). A plain `mvn package`/`install` produces a non-bundle jar that the agent
cannot start. Output: `target/repo-1.3-SNAPSHOT.jar`.

Requires `io.cresco:library` in your local Maven repository — build [library](https://github.com/CrescoEdge/library) first.
`mvn package` runs the JUnit tests (signing keys are made at test time with the JDK's keytool).

## Cresco framework

| Component | Role |
|-----------|------|
| [Agent](https://github.com/CrescoEdge/agent) | OSGi runtime that boots the framework and bundles every component into one executable jar. |
| [Logger](https://github.com/CrescoEdge/logger) | Logging bundle (pax-logging) — the first service the agent starts. |
| [Library](https://github.com/CrescoEdge/library) | Shared `io.cresco.library` API + embedded dependencies (JMS, Siddhi, Jackson, …) used by every component and plugin. |
| [Core](https://github.com/CrescoEdge/core) | Core agent services (logging control, update management), loaded above the library. |
| [Controller](https://github.com/CrescoEdge/controller) | Control plane — manages agents, regions and the global hierarchy; embeds the ActiveMQ broker, Derby state store, discovery, and loads system plugins. |
| **[Repo](https://github.com/CrescoEdge/repo)** — _this repo_ | Plugin repository — stores, reports and deploys Cresco plugins. |
| [SysInfo](https://github.com/CrescoEdge/sysinfo) | Collects operating-environment and system metrics for an agent. |
| [WSAPI](https://github.com/CrescoEdge/wsapi) | WebSocket API plugin — the external client entrypoint (control, data plane, log streaming) over `wss://…:8282`. |
| [STunnel](https://github.com/CrescoEdge/stunnel) | Secure TCP tunnel plugin (Netty) — tunnels TCP across the fabric. |
| [Java Client (clientlib)](https://github.com/CrescoEdge/clientlib) | Java client library for driving Cresco through the wsapi. |
| [Python Client (pycrescolib)](https://github.com/CrescoEdge/pycrescolib) | Python client library for driving Cresco through the wsapi. |

## Observability

Wired into Cresco's central metrics + health systems, consistent with every other plugin:

- **Metrics** — `getmetrics` returns `MeasurementEngine` gauges (`repo.plugin.count`, `repo.bytes`);
  aggregated fabric-wide by the controller's `getmetricinventory`.
- **Health** — registers a Felix `HealthCheck` (name `repo`, tag `local`) discovered by the
  controller's `CrescoHealthExecutor`; reports the plugin-jar count and surfaces in the health
  summary and the controller's `gethealthinventory` action.

## License

Apache License, Version 2.0.
