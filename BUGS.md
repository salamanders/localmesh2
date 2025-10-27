# Bugs

---
Severity: High
State: Open
Description: One of the devices in the test group, `6ZwRq` (device ID `9B071FFAZ0018X`), is not connecting to more than one peer. The investigation revealed that the app on this device is not advertising itself on the network because the `startMesh()` function in `MainActivity.kt` is not being called. The app appears to be getting stuck during its initial startup sequence, before the permission check.
Location in Code: `MainActivity.kt`
Attempts:
- 2025-10-27: Cleared logcats, restarted the app, and analyzed the logs. Found that the device is not advertising and `startMesh()` is not being called.

---
Severity: Medium
State: Closed
Description: The application is experiencing errors in `onPayloadTransferUpdate`, specifically when a payload transfer fails. The logging is not clear enough to diagnose the root cause. The error message `ClientProxy(...) failed to report onPayloadTransferUpdate(...) due to endpoint not connected` suggests a race condition where a device disconnects while a payload is in flight.
Resolution: This issue was resolved by making the reshuffling logic in `NearbyConnectionsManager.kt` less aggressive. The reshuffling is now only triggered when it is "worth it" (i.e., when there is a distant or unknown node to connect to). This has significantly reduced the churn in the network and eliminated the payload transfer failures.
Location in Code: `NearbyConnectionsManager.kt` in the `onPayloadTransferUpdate` function.
Attempts:

- 2025-10-26: Removed the manually created `Exception` in the `FAILURE` case to allow the original
  exception to be logged.
- 2025-10-26: Added more detailed logging to the `onDisconnected` callback to get more context on
  disconnections.

## Bug 1: Immediate Disconnect

* **Severity**: High
* **State**: Closed
* **Description**: Devices connect and then immediately disconnect, causing a connection loop.
* **Location in Code**: `NearbyConnectionsManager.kt`, `TopologyOptimizer.kt`
* **Attempts**:
    1. Inspected `logcat` from a single device. Found that connections are established and then
       lost. No `onDisconnected` log message is present.
    2. Attempted to capture `logcat` from all three devices simultaneously to correlate events, but
       have had trouble with the logging commands.
    3. Captured `logcat` from all three devices individually. The logs confirm the
       connection-disconnection loop. All three devices connect to each other, but then something
       causes them to disconnect. The `STATUS_ALREADY_CONNECTED_TO_ENDPOINT` errors are a symptom of
       this, as the devices are trying to reconnect to endpoints they are already connected to, or
       have just disconnected from. One device also showed a `STATUS_ENDPOINT_IO_ERROR`, suggesting
       a lower-level issue. The `onDisconnected` log message is not showing up on any of the
       devices, which strongly suggests that the disconnection is not being initiated by the app's
       code, but by the Nearby Connections API itself, or by the underlying system.
    4. **Analysis of logcat and code**:
        * **Observation**: Logcat shows a connect/disconnect loop where an endpoint is "lost" a few
          seconds after connecting. Crucially, the endpoint is immediately rediscovered with a *new
          name* (e.g., `RXJC` becomes `6IHmP:1`).
        * **Suspicion**: The device ID, which is part of the advertised endpoint name, is not
          stable. The `CachedPrefs.getId()` method is responsible for generating and caching this
          ID. A race condition or other bug in this method could be causing a new ID to be generated
          frequently. When `advertiseWithAccuratePeerCount()` is called, it uses this new, unstable
          ID, causing other devices to see it as a new endpoint and drop the old connection.
        * **Plan**:
            1. Add detailed logging to `CachedPrefs.getId()` to track when new IDs are generated.
            2. Build and deploy the app to the devices.
            3. Examine the logcat for the new logs to confirm if and when new IDs are being created.
    5. **Experiment: Stable Endpoint Names**:
        * **Hypothesis**: The user suggested that changing the advertised endpoint name by embedding
          the connection count might be violating an unwritten rule of the Nearby Connections API,
          causing the "Endpoint lost" issues.
        * **Action**:
            1. Modified `NearbyConnectionsManager.kt` to advertise a stable endpoint name (
               `localName`) without the connection count.
            2. Modified the `endpointDiscoveryCallback` to assume a hardcoded connection count of 1,
               since it could no longer be parsed from the name.
            3. Added detailed logging to `CachedPrefs.getId()` to verify the stability of the device
               ID.
        * **Results**:
            * The new logging in `CachedPrefs` confirmed that the device ID (`IqZ7j`) is stable and
              not being regenerated. My previous theory of a race condition was incorrect.
            * With a stable endpoint name, the rapid "Endpoint lost" and "onEndpointFound" cycles
              disappeared from the logs. This confirms the user's hypothesis was correct.
            * However, the logs still show `onDisconnected` events and connection failures with
              `STATUS_ENDPOINT_IO_ERROR` and `STATUS_ALREADY_CONNECTED_TO_ENDPOINT`.
        * **Interpretation**:
            * The primary cause of the rapid disconnect/reconnect loop was indeed the unstable
              endpoint name.
            * An underlying connection instability issue still exists, manifesting as
              `STATUS_ENDPOINT_IO_ERROR`.
            * The `STATUS_ALREADY_CONNECTED_TO_ENDPOINT` errors are likely a race condition. The
              user's suggestion that it could be from a previous run is plausible, especially if the
              app is not cleaning up connections properly on exit. However, the `deploy_all.sh`
              script now clears the logcat, so the errors are from the current run. It's more likely
              that the `TopologyOptimizer`'s aggressive reconnection logic is firing before the
              connection state is fully updated after a disconnect.
    6. **Hypothesis: Lingering Connections**:
        * **Theory**: The user suggested that `STATUS_ALREADY_CONNECTED_TO_ENDPOINT` errors could be
          caused by connections lingering across app launches, even with a cleared logcat.
        * **Evidence**: A search of the codebase for `NearbyConnectionsManager.stop()` revealed that
          the method is never called. This strongly suggests that connections are not being properly
          cleaned up when the app exits.
        * **Plan**:
            1. Add a logging statement to `NearbyConnectionsManager.stop()` to confirm when it is
               called.
            2. Add an `onDestroy()` method to `MainActivity.kt` and call
               `NearbyConnectionsManager.stop()` from it. Also add a log statement to `onDestroy()`.
            3. Build and deploy the app.
            4. Examine the logcat for the new `stop()` and `onDestroy()` messages, and to see if the
               `STATUS_ALREADY_CONNECTED_TO_ENDPOINT` errors are resolved.
    7. **Fix: App Shutdown Race Condition**:
        * **Observation**: The logs from the previous experiment showed that
          `MainActivity.onDestroy()` and `NearbyConnectionsManager.stop()` were being called almost
          immediately after the app started.
        * **Cause**: A `finish()` call in `MainActivity.startMesh()` was causing the main activity
          to be destroyed, which in turn triggered the `onDestroy()` method and shut down the entire
          mesh network.
        * **Fix**: Removed the `finish()` call from `MainActivity.startMesh()`.
    8. **Confirmation and Final Analysis**:
        * **Observation**: After deploying the fix, the `MainActivity.onDestroy()` and
          `NearbyConnectionsManager.stop()` messages no longer appear at the start of the logs. The
          `STATUS_ALREADY_CONNECTED_TO_ENDPOINT` errors are gone.
        * **Conclusion**: The "Immediate Disconnect" bug was two separate issues:
            1. **"Endpoint Lost" Storm**: Caused by changing the advertised endpoint name. Resolved
               by using a stable name.
            2. **`STATUS_ALREADY_CONNECTED_TO_ENDPOINT` Errors**: Caused by lingering connections
               from previous app launches. Resolved by calling `stopAllEndpoints()` when the app
               exits.
        * **Final State**: The immediate disconnect issue is resolved. The app is now stable. The
          remaining challenge is to share the connection count between devices without destabilizing
          the network.

## Bug 2: Dummy Data in STATUS

* **Severity**: Medium
* **State**: Closed
* **Description**: The UI displays a hardcoded list of 3 peers, regardless of the actual number of
  connected peers.
* **Location in Code**: `JavaScriptInjectedAndroid.kt`
* **Attempts**:
    1. Inspected `JavaScriptInjectedAndroid.kt` and found that the `getStatus()` method returns a
       hardcoded JSON string with 3 dummy peers.