# Completed Tasks

## Refactor: Separate Topology Optimizer from Connection Management

- **Goals:**
    - To refactor `NearbyConnectionsManager` by separating the low-level connection management from the high-level topology optimization logic.
    - To improve code clarity, flexibility, and testability.
    - To follow the "Composition over Inheritance" design principle.
- **Design:**
    - Split the existing `NearbyConnectionsManager` into two distinct classes:
        1.  **`NearbyConnectionsManager` (Refactored):** Responsible only for direct interactions with the `com.google.android.gms.nearby.connection` API.
        2.  **`TopologyOptimizer` (New Class):** Contains all high-level logic for maintaining the desired mesh topology, with no direct dependencies on the Nearby Connections API.
- **List of Changes:**
    - [x] Create the new file `app/src/main/java/info/benjaminhill/localmesh2/TopologyOptimizer.kt`.
    - [x] Define the `TopologyOptimizer` class structure with placeholder methods.
    - [x] Move the reshuffling logic (`startReshuffling`, `reshuffleJob`) from `NearbyConnectionsManager` to `TopologyOptimizer`.
    - [x] Move the topology-related decision logic from `endpointDiscoveryCallback` in `NearbyConnectionsManager` into `TopologyOptimizer`.
    - [x] Move the topology-related decision logic from `onDisconnected` in `NearbyConnectionsManager` into `TopologyOptimizer`.
    - [x] In `NearbyConnectionsManager`, create an instance of `TopologyOptimizer`.
    - [x] Update `NearbyConnectionsManager` to call the new methods on `TopologyOptimizer` to make decisions.
    - [x] Remove all `com.google.android.gms.nearby.connection` imports and dependencies from `TopologyOptimizer`.
    - [x] Verify the refactoring by building the project.
    - [ ] Delete `REFACTOR_TOPOLOGY_OPTIMIZER.md`.

# Lessons Learned

## Bad Ideas to Avoid

### 1. "God Object" Service
The `BridgeService` in `localmesh` was a bit of a "god object." It had direct knowledge and control over almost every other component (`NearbyConnectionsManager`, `LocalHttpServer`, `ServiceHardener`, etc.). This tight coupling makes the code difficult to test and maintain. `localmesh2` has already made progress here by separating the `TopologyOptimizer` from the `NearbyConnectionsManager`, and this separation of concerns should continue.

### 2. Rigid "Snake" Topology
The `SnakeConnectionAdvisor` enforced a strict "snake" topology with a maximum of two connections per node. While simple, this topology is not very resilient. A single node failure can easily partition the network into two separate islands. The more flexible mesh topology in `localmesh2` (aiming for 2-4 connections) is a much more robust approach.

### 3. Hardcoded Broadcast Paths
In `LocalHttpServer`, the list of API paths that should be broadcast to the mesh was hardcoded in a `BROADCAST_PATHS` set. This is inflexible. A better approach would be to use a more dynamic method, such as annotations on the Ktor route handlers, to determine which endpoints should be broadcast.

### 4. Simple Random Endpoint Names
`localmesh` generated a simple 5-character random string for the device's endpoint name. In a large network, this could lead to collisions. The approach in `localmesh2` of using a short hash of a UUID is statistically much safer.

## Learnings from Past Documents

### Key Findings from `P2P_DOCS.md`
This document provided a deep dive into the `P2P_CLUSTER` strategy, and its lessons are critical for `localmesh2`:

*   **Practical Connection Limit:** The practical limit for stable connections per device is **3 to 4**, not the theoretical maximum of 7. `localmesh2`'s `TopologyOptimizer` should respect this limit.
*   **Bluetooth Only:** `P2P_CLUSTER` operates exclusively over Bluetooth when there is no external Wi-Fi router. It does **not** upgrade to Wi-Fi Direct. This explains the lower bandwidth and makes the 3-4 device limit a hard constraint.
*   **Authentication Tokens:** The API provides an authentication token during the connection handshake. The original project did not use this, but `localmesh2` should consider implementing a manual, UI-driven verification of this token to prevent man-in-the-middle attacks.

### Open Bugs and Issues from `BUG_REPORT.md`
The following issues from the original project are worth noting to avoid repeating them:

*   **WebView Permission Vulnerability:** The old WebView was configured to auto-grant all permissions (camera, mic, etc.). This was marked as "Won't Fix" for the demo, but is a major security risk that `localmesh2` must avoid.
*   **Race Conditions:** Several race conditions were identified in the `TopologyOptimizer` and `DisplayActivity` of the original project. As `localmesh2` develops similar features, it should be mindful of these potential issues:
    *   Multiple connection requests being fired before the connection count is updated.
    *   Network rewiring and island discovery logic running concurrently.
    *   Multiple intents arriving at the `DisplayActivity` in quick succession, causing the wrong URL to be loaded.
*   **WebView Resource Leaks:** The bug report noted a potential for resource leaks if the WebView is not explicitly destroyed. `localmesh2` should ensure its `WebAppActivity` handles the WebView lifecycle correctly.

### Workflow Analysis from `ANALYSIS_CAMERA.md`
The camera-to-slideshow workflow analysis revealed two key problems that `localmesh2` should solve if it implements a similar feature:

1.  **Race Condition:** The command to open the slideshow on a peer device would often arrive *before* the image file transfer was complete. This resulted in the slideshow opening without the new image, which would only appear after a polling interval.
2.  **Polling Inefficiency:** The slideshow relied on polling to discover new images. A push-based mechanism (e.g., a WebSocket or a dedicated "new image" message) would be far more efficient and provide a better user experience.

### General Notes
*   **OS-Level Issues:** As noted in `BAD_NETWORK.md`, be aware of potential OS-level issues, such as the system denying necessary permissions, which can be difficult to debug.
*   **Testing Topology Logic:** The `NEW_DEVELOPER_TESTING_GUIDE.md` highlighted that automated testing of complex, emergent network behavior can be flaky. Manual testing on real devices remains the most reliable way to verify topology optimization logic.

# Potential Future Features

## Roadmap: Concrete Next Steps

### Ktor-based Local HTTP Server
The original project used a Ktor-based HTTP server running on each device. This provided two major benefits:

*   **Web-based UI:** It allowed for a rich, web-based user interface for the mesh, served directly from the device.
*   **Mesh-wide API:** A `p2pBroadcastInterceptor` would intercept certain local HTTP requests and broadcast them to all peers. This created a simple, yet powerful, API for controlling the entire mesh.
*   **HTTP Request Wrapping:** The `HttpRequestWrapper` data class was a clean and effective way to serialize and broadcast "API calls" (as HTTP requests) across the mesh.
*   **API Reference (from original project):**
    *   `GET /list?type=folders`: Lists the available content folders.
    *   `GET /status`: Retrieves the current service status, device ID, and peer list.
    *   `POST /chat`: Sends a chat message to all peers.
    *   `GET /display`: Triggers the `WebAppActivity` on remote peers.
    *   `POST /send-file`: Initiates a file transfer.
    *   `GET /{path...}`: Serves static files.

### File Sharing with Reassembly
The original project had a `FileReassemblyManager` and a `FileChunk` message type to handle sharing large files over the mesh. Files were broken into chunks, transmitted, and then reassembled on the receiving devices. `localmesh2` currently lacks any file sharing capabilities.

### Enhancing the Gossip Protocol
The current `NetworkMessage` is effective for simple, text-based data. A future goal is to evolve this into a truly "Unified" protocol by enhancing the `NetworkMessage` data class to handle multiple, strongly-typed data payloads. A unified protocol would treat all data—commands, API calls, and file data alike—as different types within the same standard `NetworkMessage` wrapper. The `SnakeConnectionAdvisor` in `localmesh` used an explicit gossip protocol to share the network topology, which is a robust pattern to consider.

### Asset Unpacking for Web UI
The `AssetManager` in `localmesh` had a utility to unpack all web assets (HTML, CSS, JS) from the APK's `assets` folder into the app's file directory. This made them easily servable by the Ktor server.

## Wild Ideas: Speculative Concepts

### `ServiceHardener` Watchdog
`localmesh` included a `ServiceHardener` class that acted as a watchdog for the application. It would periodically check the health of the local Ktor web server, the P2P network, and the WebView UI. If any of these components were found to be unresponsive, the `ServiceHardener` could automatically restart the main service.

### Standalone Visualization Tester
The `visualization-tester` module in the original project was a separate Android application dedicated to testing the web-based visualizations. This is a great development practice that allows for rapid iteration on the UI without needing to run the full mesh stack.

### `BridgeService`
A foreground `Service` that orchestrates all the components. This would keep the mesh network alive even when the app is not in the foreground.
