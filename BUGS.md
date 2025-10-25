# Bugs

## Bug 1: Immediate Disconnect

*   **Severity**: High
*   **State**: Open
*   **Description**: Devices connect and then immediately disconnect, causing a connection loop.
*   **Location in Code**: `NearbyConnectionsManager.kt`, `TopologyOptimizer.kt`
*   **Attempts**:
    1.  Inspected `logcat` from a single device. Found that connections are established and then lost. No `onDisconnected` log message is present.
    2.  Attempted to capture `logcat` from all three devices simultaneously to correlate events, but have had trouble with the logging commands.
    3.  Captured `logcat` from all three devices individually. The logs confirm the connection-disconnection loop. All three devices connect to each other, but then something causes them to disconnect. The `STATUS_ALREADY_CONNECTED_TO_ENDPOINT` errors are a symptom of this, as the devices are trying to reconnect to endpoints they are already connected to, or have just disconnected from. One device also showed a `STATUS_ENDPOINT_IO_ERROR`, suggesting a lower-level issue. The `onDisconnected` log message is not showing up on any of the devices, which strongly suggests that the disconnection is not being initiated by the app's code, but by the Nearby Connections API itself, or by the underlying system.

## Bug 2: Dummy Data in STATUS

*   **Severity**: Medium
*   **State**: Open
*   **Description**: The UI displays a hardcoded list of 3 peers, regardless of the actual number of connected peers.
*   **Location in Code**: `JavaScriptInjectedAndroid.kt`
*   **Attempts**:
    1.  Inspected `JavaScriptInjectedAndroid.kt` and found that the `getStatus()` method returns a hardcoded JSON string with 3 dummy peers.
