# Migration Ideas from `localmesh`

This document lists several "good ideas" from the original `localmesh` project that could be
considered for implementation in `localmesh2`.

## 1. Ktor-based Local HTTP Server

The original project used a Ktor-based HTTP server running on each device. This provided two major
benefits:

* **Web-based UI:** It allowed for a rich, web-based user interface for the mesh, served directly
  from the device.
* **Mesh-wide API:** A `p2pBroadcastInterceptor` would intercept certain local HTTP requests and
  broadcast them to all peers. This created a simple, yet powerful, API for controlling the entire
  mesh (e.g., making all devices display a certain video).

This is a more flexible and extensible approach than the current `JavaScriptInjectedAndroid`
interface in `localmesh2`.

## 2. `ServiceHardener` Watchdog

`localmesh` included a `ServiceHardener` class that acted as a watchdog for the application. It
would periodically check the health of:

* The local Ktor web server.
* The P2P network (e.g., checking for connectivity, message flow).
* The WebView UI.

If any of these components were found to be unresponsive, the `ServiceHardener` could automatically
restart the main service. This is an excellent feature for ensuring the long-term stability of a
decentralized, long-running application.

## 3. File Sharing with Reassembly

The original project had a `FileReassemblyManager` and a `FileChunk` message type to handle sharing
large files over the mesh. Files were broken into chunks, transmitted, and then reassembled on the
receiving devices. `localmesh2` currently lacks any file sharing capabilities.

## 4. Asset Unpacking for Web UI

The `AssetManager` in `localmesh` had a utility to unpack all web assets (HTML, CSS, JS) from the
APK's `assets` folder into the app's file directory. This made them easily servable by the Ktor
server. This is a good pattern for managing and serving a web-based UI.

## 5. Explicit Gossip Protocol for Topology

The `SnakeConnectionAdvisor` in `localmesh` used an explicit gossip protocol to share the network
topology (the "snake"). While `localmesh2` has a `TopologyOptimizer`, the explicit and distinct
gossip mechanism for sharing network state is a robust pattern to consider for more complex topology
information.

## 6. Standalone Visualization Tester

The `visualization-tester` module in the original project was a separate Android application
dedicated to testing the web-based visualizations. This is a great development practice that allows
for rapid iteration on the UI without needing to run the full mesh stack.

## 7. HTTP Request Wrapping

The `HttpRequestWrapper` data class was a clean and effective way to serialize and broadcast "API
calls" (as HTTP requests) across the mesh. This is a core component of the Ktor-based mesh API.

---

## Bad Ideas to Avoid

Here are some concepts from `localmesh` that we should probably not carry over.

### 1. "God Object" Service

The `BridgeService` in `localmesh` was a bit of a "god object." It had direct knowledge and control
over almost every other component (`NearbyConnectionsManager`, `LocalHttpServer`, `ServiceHardener`,
etc.). This tight coupling makes the code difficult to test and maintain. `localmesh2` has already
made progress here by separating the `TopologyOptimizer` from the `NearbyConnectionsManager`, and
this separation of concerns should continue.

### 2. Rigid "Snake" Topology

The `SnakeConnectionAdvisor` enforced a strict "snake" topology with a maximum of two connections
per node. While simple, this topology is not very resilient. A single node failure can easily
partition the network into two separate islands. The more flexible mesh topology in `localmesh2` (
aiming for 2-4 connections) is a much more robust approach.

### 3. Hardcoded Broadcast Paths

In `LocalHttpServer`, the list of API paths that should be broadcast to the mesh was hardcoded in a
`BROADCAST_PATHS` set. This is inflexible. A better approach would be to use a more dynamic method,
such as annotations on the Ktor route handlers, to determine which endpoints should be broadcast.

### 4. Simple Random Endpoint Names

`localmesh` generated a simple 5-character random string for the device's endpoint name. In a large
network, this could lead to collisions. The approach in `localmesh2` of using a short hash of a UUID
is statistically much safer.

---

## Learnings from Past Documents

This section captures important notes, bugs, and design lessons from the markdown files in the
original `localmesh` project.

### Key Findings from `P2P_DOCS.md`

This document provided a deep dive into the `P2P_CLUSTER` strategy, and its lessons are critical for
`localmesh2`:

* **Practical Connection Limit:** The practical limit for stable connections per device is **3 to 4
  **, not the theoretical maximum of 7. `localmesh2`'s `TopologyOptimizer` should respect this
  limit.
* **Bluetooth Only:** `P2P_CLUSTER` operates exclusively over Bluetooth when there is no external
  Wi-Fi router. It does **not** upgrade to Wi-Fi Direct. This explains the lower bandwidth and makes
  the 3-4 device limit a hard constraint.
* **Authentication Tokens:** The API provides an authentication token during the connection
  handshake. The original project did not use this, but `localmesh2` should consider implementing a
  manual, UI-driven verification of this token to prevent man-in-the-middle attacks.

### Open Bugs and Issues from `BUG_REPORT.md`

The following issues from the original project are worth noting to avoid repeating them:

* **WebView Permission Vulnerability:** The old WebView was configured to auto-grant all
  permissions (camera, mic, etc.). This was marked as "Won't Fix" for the demo, but is a major
  security risk that `localmesh2` must avoid.
* **Race Conditions:** Several race conditions were identified in the `TopologyOptimizer` and
  `DisplayActivity` of the original project. As `localmesh2` develops similar features, it should be
  mindful of these potential issues:
    * Multiple connection requests being fired before the connection count is updated.
    * Network rewiring and island discovery logic running concurrently.
    * Multiple intents arriving at the `DisplayActivity` in quick succession, causing the wrong URL
      to be loaded.
* **WebView Resource Leaks:** The bug report noted a potential for resource leaks if the WebView is
  not explicitly destroyed. `localmesh2` should ensure its `WebAppActivity` handles the WebView
  lifecycle correctly.

### Workflow Analysis from `ANALYSIS_CAMERA.md`

The camera-to-slideshow workflow analysis revealed two key problems that `localmesh2` should solve
if it implements a similar feature:

1. **Race Condition:** The command to open the slideshow on a peer device would often arrive
   *before* the image file transfer was complete. This resulted in the slideshow opening without the
   new image, which would only appear after a polling interval.
2. **Polling Inefficiency:** The slideshow relied on polling to discover new images. A push-based
   mechanism (e.g., a WebSocket or a dedicated "new image" message) would be far more efficient and
   provide a better user experience.

### General Notes

* **OS-Level Issues:** As noted in `BAD_NETWORK.md`, be aware of potential OS-level issues, such as
  the system denying necessary permissions, which can be difficult to debug.
* **Testing Topology Logic:** The `NEW_DEVELOPER_TESTING_GUIDE.md` highlighted that automated
  testing of complex, emergent network behavior can be flaky. Manual testing on real devices remains
  the most reliable way to verify topology optimization logic.

# Refactor: Separate Topology Optimizer from Connection Management

## Goals

- To refactor `NearbyConnectionsManager` by separating the low-level connection management from the
  high-level topology optimization logic.
- To improve code clarity, flexibility, and testability.
- To follow the "Composition over Inheritance" design principle.

## Design

We will split the existing `NearbyConnectionsManager` into two distinct classes:

1. **`NearbyConnectionsManager` (Refactored):**

    - This class will be responsible *only* for the direct interactions with the
      `com.google.android.gms.nearby.connection` API.
    - It will manage the connection lifecycle, advertising, discovery, and payload transfers.
    - It will hold a reference to a `TopologyOptimizer` and will consult it when making decisions.
    - It will not contain any logic specific to the "snake" topology (e.g., reshuffling, connection
      count rules).

2. **`TopologyOptimizer` (New Class):**

    - This class will contain all the high-level logic for maintaining the desired mesh topology.
    - It will be responsible for the "reshuffling" logic.
    - It will provide decision-making methods like
      `shouldConnectTo(endpointId, theirConnectionCount)` and `selectEndpointToDisconnect()`.
    - **Crucially, this class will have no `import` statements
      from `com.google.android.gms.nearby.connection`.** It will work with pure data types (Strings,
      Ints, Maps).

## List of Changes

- [x] Create the new file `app/src/main/java/info/benjaminhill/localmesh2/TopologyOptimizer.kt`.
- [x] Define the `TopologyOptimizer` class structure with placeholder methods.
- [x] Move the reshuffling logic (`startReshuffling`, `reshuffleJob`) from
  `NearbyConnectionsManager` to `TopologyOptimizer`.
- [x] Move the topology-related decision logic from `endpointDiscoveryCallback` in
  `NearbyConnectionsManager` into `TopologyOptimizer`.
- [x] Move the topology-related decision logic from `onDisconnected` in `NearbyConnectionsManager`
  into `TopologyOptimizer`.
- [x] In `NearbyConnectionsManager`, create an instance of `TopologyOptimizer`.
- [x] Update `NearbyConnectionsManager` to call the new methods on `TopologyOptimizer` to make
  decisions.
- [x] Remove all `com.google.android.gms.nearby.connection` imports and dependencies from
  `TopologyOptimizer`.
- [x] Verify the refactoring by building the project.
- [ ] Delete `REFACTOR_TOPOLOGY_OPTIMIZER.md`.

### FUTURE: Enhancing the Gossip Protocol

The current `NetworkMessage` is effective for simple, text-based data. A future goal is to evolve
this into a truly "Unified" protocol, as seen in the original `localmesh` project, by enhancing the
`NetworkMessage` data class to handle multiple, strongly-typed data payloads.

**Core Concept:** A unified protocol would treat **all** data—commands, API calls, and file data
alike—as different types within the same standard `NetworkMessage` wrapper.

**Example of an Enhanced `NetworkMessage`:**

```kotlin
@Serializable
data class NetworkMessage(
    val messageId: String = UUID.randomUUID().toString(),
    val hopCount: Int = 0,

    // FUTURE: Explicit type for API calls
    val httpRequest: HttpRequestWrapper? = null,

    // FUTURE: Explicit type for file transfers
    val fileChunk: FileChunk? = null,

    // Current implementation for simple messages
    val messageContent: String? = null
)
```

This would allow the "Check, Process, Forward" logic to seamlessly handle different kinds of data,
making it possible to add features like file sharing and a mesh-wide API without ambiguity. The
receiver would simply check which field is not null to know how to process the payload.

### FUTURE: Potential Architectural Improvements

The original `localmesh` project used a more complex architecture that could be beneficial to adopt
in the future.

* **FUTURE: `BridgeService`:** A foreground `Service` that orchestrates all the components. This
  would keep the mesh network alive even when the app is not in the foreground.
* **FUTURE: `LocalHttpServer`:** A Ktor-based HTTP server that serves the web UI and provides a full
  API for the frontend. This is a more powerful and flexible alternative to the current JavaScript
  bridge.
* **FUTURE: `FileReassemblyManager`:** A manager class to handle incoming file chunks, reassemble
  them, and save them to disk, enabling file sharing.
* **FUTURE: `ServiceHardener`:** A watchdog service that monitors the health of the application and
  can restart it if it becomes unresponsive.

## 8. FUTURE: API Reference

If a Ktor-based `LocalHttpServer` is implemented, the following API endpoints from the original
project could be a good starting point:

* `GET /list?type=folders`: Lists the available content folders.
* `GET /status`: Retrieves the current service status, device ID, and peer list.
* `POST /chat`: Sends a chat message to all peers.
* `GET /display`: Triggers the `WebAppActivity` on remote peers.
* `POST /send-file`: Initiates a file transfer.
* `GET /{path...}`: Serves static files.

# Refactor: Automatic Start on Permission Grant

## Goal

- To improve the user experience by automatically starting the mesh service as soon as the necessary
  permissions are granted.
- To remove the need for the user to manually click an "Authorize Mesh" button after granting
  permissions.
- To make the app launch seamlessly, whether started manually or via a script.

## Changes

- [x] Modified `MainActivity.kt` to check for all required dangerous permissions upon launch.
- [x] If all permissions are granted, the app now automatically calls `startMesh()`, bypassing the
  authorization button.
- [x] This behavior applies to all app launches, making the manual and scripted launch experiences
  consistent.
- [x] Removed the now-redundant `--ez auto_start true` flag from the `deploy_all.sh` deployment
  script.
- [x] Verified that the permission checking is comprehensive and covers all requirements for the
  Nearby Connections API.

# Feature: CBOR Serialization for NetworkMessage

## Goals

- Replace JSON serialization with CBOR for `NetworkMessage` objects.
- Reduce message size and improve serialization/deserialization performance.
- Maintain JSON serialization for `JavaScriptInjectedAndroid`.

## Design

- Add the `kotlinx-serialization-cbor` dependency to the project.
- Update the `NetworkMessage.kt` file to use `kotlinx.serialization.cbor.Cbor` for `toByteArray` and
  `fromByteArray` methods.
- No changes to `JavaScriptInjectedAndroid.kt`.

## Checklist

- [X] Create `CBOR.md`
- [X] Add `kotlinx-serialization-cbor` dependency to `app/build.gradle.kts`.
- [X] Modify `NetworkMessage.kt` to use `Cbor` instead of `Json`.
- [X] Verify the change by building the project.
- [X] Complete pre-commit steps.
- [X] Submit the change.

# Future Features from `TopologyOptimizer`

The `docs/TODOs.md` file contained a commented-out implementation of a `TopologyOptimizer` object.
While the `reshuffle` logic has been implemented directly in `NearbyConnectionsManager` for
simplicity, the `TopologyOptimizer` contained several valuable ideas for future improvements.

## 1. Intelligent Connection Decisions

* **Concept:** The original `shouldConnectTo` function in `TopologyOptimizer` considered the
  connection count of the remote endpoint when deciding whether to connect. This information would
  have been encoded in the endpoint name.
* **Value:** This would allow the node to make more intelligent connection decisions, for example,
  by prioritizing connections to nodes with fewer peers. This would help to build a more balanced
  and robust network topology.

## 2. Safe Disconnection Logic (Implemented)

* **Concept:** A peer should only be dropped if it remains reachable through a 2-hop path.
* **Implementation:** The `findRedundantPeer` function now calculates a "redundancy score" for each
  direct peer. The score is the number of other peers that also provide a path to the candidate
  peer. The peer with the highest score is chosen to be dropped. This ensures network connectivity
  is maintained when making room for new nodes.

## 3. Proactive Churn for Network Discovery

* **Concept:** The `TopologyOptimizer` contained logic to proactively drop a well-connected peer if
  all of its current peers were also well-connected.
* **Value:** This "proactive churn" would help the network to discover new nodes and prevent it from
  stagnating in a stable but suboptimal topology. This is especially important for discovering new
  islands.

## 4. Aggressive Reconnection

* **Concept:** The `onDisconnected` function in `TopologyOptimizer` contained logic to "aggressively
  reconnect" to a new peer if the number of connections dropped below a minimum threshold.
* **Value:** This would ensure that the node always maintains a minimum level of connectivity,
  making the network more resilient to node failures.

# Feature: "SlowConnect" Mandate-and-Verify Join Algorithm

## Goal

- To simplify the network connection logic by removing complex reshuffling and gossip mechanisms.
- To implement a more robust and predictable "Mandate-and-Verify" algorithm for new nodes joining
  the mesh.
- To reduce the complexity of `TopologyOptimizer.kt` and `NearbyConnectionsManager.kt`.

## Changes

- [x] **Removed `reshuffle` and `gossip`:** The `reshuffleJob` and `gossipJob` have been removed
  from `TopologyOptimizer.kt`. The corresponding `findRedundantPeer` and `findWorstDistantNode`
  functions were also removed.
- [x] **Implemented `ensureConnectionsJob`:** A new primary loop in `TopologyOptimizer.kt` now
  ensures that the node establishes a mandatory first connection and then makes a best-effort
  attempt to secure a second.
- [x] **Updated Connection Logic:** The `onConnectionInitiated` logic in
  `NearbyConnectionsManager.kt` has been simplified to reject incoming connections if the node is
  already at its limit (5), rather than trying to disconnect a redundant peer.
- [x] **Updated `onEndpointFound`:** The `onEndpointFound` function in `TopologyOptimizer.kt` now
  adds newly discovered endpoints to a simple set of `availablePeers` for the `ensureConnectionsJob`
  to use.