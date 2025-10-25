# LocalMesh2 Project Technical Guide

## 1. Project Overview

This is an Android application that creates a peer-to-peer mesh network using the Google Nearby
Connections API. The application, named "localmesh2", allows devices to discover and connect to each
other, forming a resilient and self-healing network. The primary function is to broadcast small
messages to all nodes in the network.

The main user interface is a web-based dashboard that shows the status of the mesh network,
including the device ID, P2P status, and the number of peers. It also lists available
"visualizations" which are other HTML files in the `assets` folder.

The application is written in Kotlin and uses Gradle for building and Jetpack Compose for the UI.

---

## 2. System Architecture

The system is composed of these main components:

* **Web Frontend:** A user-facing single-page application running in a `WebView`. It
  interacts with the backend through a JavaScript interface.
* **`localmesh2` Android App:** The native Android application that provides the
  Android-specific implementations and UI.
    * `NearbyConnectionsManager`: Wraps the Google Play Services Nearby Connections API and acts as
      the "hands" of the network, translating commands from the `TopologyOptimizer` into hardware
      operations.
    * `TopologyOptimizer`: The "brains" of the network. It contains all the logic for analyzing
      network health and making high-level decisions to optimize the network topology.
    * `WebAppActivity`: The Android activity for hosting the `WebView`.
    * `JavaScriptInjectedAndroid`: The bridge that allows communication between the WebView frontend
      and the Kotlin backend.
* **Peers:** Other Android devices on the same local network running the LocalMesh2 app.

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

---

## 3. Communication Protocol

The project uses a gossip protocol to broadcast `NetworkMessage` objects to all connected peers. The
`NearbyConnectionsManager` implements a "Check, Process, Forward" mechanism to ensure messages reach
every node exactly once while preventing infinite loops.

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

---

## 4. Building and Running

This is a standard Android project. It can be built and run using Android Studio or the `gradlew`
command-line tool.

```bash
./gradlew build
./gradlew installDebug
```

---

## 5. Documented Development

This project follows a strict, evidence-based development process.

### The "Prove-It" Workflow

To prevent incorrect assumptions and premature declarations of success, the following workflow is
mandatory for all tasks.

1. **State the Hypothesis:** Before attempting any fix, explicitly state the hypothesis for the root
   cause of the problem.
2. **Gather Evidence:** Use discovery tools (`read_file`, `search_file_content`, etc.) to gather
   evidence that proves or disproves the hypothesis.
3. **Announce the Cause:** Announce the confirmed root cause before proposing a fix.
4. **Execute a Single, Atomic Change:** Make the smallest possible change to address the cause.
5. **Immediately Verify with Proof:** After every single action, run a command to get positive proof
   of the outcome.
    * After a code change (`write_file`, `replace`): Immediately run the build (`./gradlew build`).
    * After an `adb` command: Immediately run `adb logcat -d -t 1` or a more specific `logcat`
      filter to find affirmative proof that the action succeeded.
    * **Absence of an error is not proof of success.** Only a log message or status confirming the
      intended outcome is proof.
6. **Announce Only After Proof:** Never state a task is "done" or "fixed" until you have the output
   from the verification command.

### Feature and Bug Tracking

* **Features:** Large features must be planned in a `{FEATURE_NAME}.md` file. The file should
  include goals, design, and a checklist of changes. A feature is not complete until the code is
  compiled, lint-checked, and auto-formatted.
* **Bugs:** All bugs must be documented in `BUGS.md`. Each bug should have a "Severity", "State" (
  open, closed, wont_fix), "Description", "Location in Code", and "Attempts".

---

## 6. Development Guidelines

### Code Style & Principles

1. **Simplicity:** Strongly prefer simple, straightforward, and concise code.
2. **Modern Syntax:** Use modern Kotlin features where appropriate, especially for string parsing.
3. **Fluent Syntax:** Prefer fluent syntax (`apply`, `also`) and early returns over deeply nested
   if-statements.
4. **No Magic Numbers:** Avoid magic numbers (e.g., `val uid = String(bytes.copyOfRange(0, 36))`).
   Use constants or data classes instead.
5. **Search Before Refactoring:** Before refactoring shared code, perform a global search to
   identify all usages to prevent breaking changes.

### Agent Behavior & Mentality

1. **Be Pessimistic & Skeptical:** Never assume a fix will work without verification. Critically
   evaluate all suggestions, including the user's. Provide balanced pros and cons.
2. **Assume Flaky Devices:** Design for a network where devices can crash, run out of battery, or
   disconnect at any time.
3. **Don't Get Stuck:** If you have a failure more than 3 times in a row (e.g., a dependency issue),
   halt and ask the user for help.
4. **Don't Be Obsequious:** Avoid conversational filler. Be direct and professional.
5. **Document Reverts:** If you revert a change, document it in a markdown file so the mistake is
   not repeated.
6. **Leave Logs:** Bias towards leaving new logging statements in the code.
7. **Utilize the User:** For large, repetitive tasks (e.g., 10+ search-and-replace), ask the user to
   perform them.

---

## 7. Technical Sticking Points

* **GUESSING WITHOUT EVIDENCE:** Do not guess. Take small, evidence-backed steps.
* **KOTLINX.SERIALIZATION BINARY FORMATS:** `kotlinx.serialization.json.Json` is a `StringFormat`,
  not a `BinaryFormat`. It cannot directly encode to/from a `ByteArray` without an intermediate
  `String` conversion. For true binary serialization, a `BinaryFormat` like `ProtoBuf` or `Cbor` is
  required.
* **Logcat Filtering:** `logcat` output is too large to read without filters. Start with tight
  filters and relax them as needed.
* **Process IDs:** The process ID changes on every run. Filter logs accordingly.
* **Skipping Large Files:** Always skip reading the content of minified JavaScript libraries like
  `three.min.js` or `Tween.min.js`.

---

## 8. FUTURE: API Reference

If a Ktor-based `LocalHttpServer` is implemented, the following API endpoints from the original
project could be a good starting point:

* `GET /list?type=folders`: Lists the available content folders.
* `GET /status`: Retrieves the current service status, device ID, and peer list.
* `POST /chat`: Sends a chat message to all peers.
* `GET /display`: Triggers the `WebAppActivity` on remote peers.
* `POST /send-file`: Initiates a file transfer.
* `GET /{path...}`: Serves static files.

---

## 9. Automated Testing

The original project had a robust method for end-to-end testing using `adb` and `curl`. This is a
good model to follow.

### Test Workflow

1. **Build and Grant Permissions:** Build a debug APK and install it with the `-g` flag to
   auto-grant permissions.
   ```bash
   ./gradlew assembleDebug
   adb install -r -g app/build/outputs/apk/debug/app-debug.apk
   ```
2. **Automated App Launch:** Use `adb` to start the `MainActivity` with a special `auto_start` flag
   to bypass the UI and start the service directly.
   ```bash
   adb shell am start -n info.benjaminhill.localmesh2/.MainActivity --ez auto_start true
   ```
3. **Forward Device Port (FUTURE):** Forward the device's port to your local machine to enable
   `curl` commands to a local server.
   ```bash
   adb forward tcp:8099 tcp:8099
   ```
4. **Trigger an Action via API (FUTURE):** Use `curl` to send commands to the app's local server.
   ```bash
   # Example: Trigger the 'motion' display on the connected device
   curl -X GET "http://localhost:8099/display?path=motion&sourceNodeId=test-node"
   ```
5. **Monitor for Proof:** Check `logcat` for logs confirming the action was received and executed
   correctly.
   ```bash
   adb logcat -d WebAppActivity:I *:S
   ```
6. **Clean Up:**
   ```bash
   adb forward --remove-all
   ```
