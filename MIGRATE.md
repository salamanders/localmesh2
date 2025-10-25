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
