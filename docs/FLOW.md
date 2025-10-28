# Network Connection and Message Flows

This document details the step-by-step process for key interactions within the STAR topology network.

## Flow 1: PhoneA Becomes the General (Main Hub)

1.  **App Start:** `MainActivity` is launched on PhoneA.
2.  **UI Choice:** For 10 seconds, a "Become Remote Control" button is displayed.
3.  **User Action:** The user taps the "Become Remote Control" button.
4.  **Role Assignment:** PhoneA is now assigned the role of **General**.
5.  **Start Advertising:**
    *   The `NearbyConnectionsManager` calls the `startAdvertising()` method from the Google Nearby Connections API.
    *   **`serviceId`:** `HUB_SERVICE_ID` ("localmesh2-hub-level") is used. This ensures only potential Lieutenants can see this device.
    *   **`strategy`:** `Strategy.P2P_STAR` is used.
    *   **`ConnectionLifecycleCallback`:** A callback is provided to handle connection events.
6.  **Ready State:** PhoneA is now advertising and waiting for Lieutenants to connect. It will accept up to `MAX_LIEUTENANTS` (e.g., 5) connections. Any further incoming requests will be rejected within the `onConnectionInitiated` callback.

## Flow 2: PhoneB Connects as a Lieutenant

1.  **App Start:** `MainActivity` is launched on PhoneB.
2.  **UI Timeout:** The 10-second window to become the General passes without the button being pressed.
3.  **Default to Lieutenant:** The app logic defaults to the **Lieutenant** role.
4.  **Start Discovery (for Hub):**
    *   `NearbyConnectionsManager` calls `startDiscovery()`.
    *   **`serviceId`:** `HUB_SERVICE_ID` is used to find the General.
    *   **`strategy`:** `Strategy.P2P_STAR`.
    *   **`EndpointDiscoveryCallback`:** A callback is provided to handle discovery events.
5.  **Endpoint Found:**
    *   The `onEndpointFound` callback is triggered when PhoneB discovers PhoneA.
    *   `NearbyConnectionsManager` immediately calls `requestConnection()` to connect to PhoneA.
6.  **Connection Handshake (on General - PhoneA):**
    *   The `onConnectionInitiated` callback is triggered on PhoneA.
    *   The logic checks if `lieutenantEndpointIds.size() < MAX_LIEUTENANTS`. It is, so...
    *   PhoneA calls `acceptConnection()`.
7.  **Connection Result (on Lieutenant - PhoneB):**
    *   The `onConnectionResult` callback is triggered on PhoneB with `STATUS_OK`.
    *   PhoneB stores the endpoint ID for PhoneA (`mainHubEndpointId`).
8.  **Become a Lieutenant:**
    *   Now successfully connected to the General, PhoneB's `TopologyOptimizer` logic transitions it fully to the Lieutenant role.
    *   It calls `startAdvertising()` using the **`CLIENT_SERVICE_ID`** ("localmesh2-client-level").
9.  **Ready State:** PhoneB is now connected to the General and is advertising for Leafs to connect.

## Flow 3: PhoneC is Demoted to a Leaf

1.  **Pre-condition:** The General (PhoneA) already has the maximum number of Lieutenants connected (`MAX_LIEUTENANTS`).
2.  **App Start:** `MainActivity` is launched on PhoneC.
3.  **Default to Lieutenant:** The 10-second UI window expires, and PhoneC defaults to the **Lieutenant** role.
4.  **Start Discovery (for Hub):** PhoneC starts discovery using `HUB_SERVICE_ID`.
5.  **Endpoint Found:** PhoneC discovers the General (PhoneA) and calls `requestConnection()`.
6.  **Connection Handshake (on General - PhoneA):**
    *   The `onConnectionInitiated` callback is triggered on PhoneA.
    *   The logic checks if `lieutenantEndpointIds.size() < MAX_LIEUTENANTS`. The check fails (it is full).
    *   PhoneA calls `rejectConnection()`.
7.  **Connection Result (on Demoted Phone - PhoneC):**
    *   The `onConnectionResult` callback is triggered on PhoneC with a failure status (e.g., `STATUS_CONNECTION_REJECTED`).
    *   The logic identifies this as a "demotion" event. `mainHubEndpointId` remains `null`.
8.  **Transition to Leaf:**
    *   The `TopologyOptimizer` on PhoneC transitions the phone to the **Leaf** role.
    *   It stops discovering for the Hub (`HUB_SERVICE_ID`).
    *   It starts a new discovery process, this time for Lieutenants, using **`CLIENT_SERVICE_ID`**.
9.  **Discover Lieutenant:** PhoneC's discovery finds PhoneB advertising. It calls `requestConnection()`.
10. **Connection Handshake (on Lieutenant - PhoneB):**
    *   The `onConnectionInitiated` callback fires on PhoneB.
    *   The logic checks if `clientEndpointIds.size() < MAX_CLIENTS_PER_LIEUTENANT`. It is not full.
    *   PhoneB calls `acceptConnection()`.
11. **Connection Result (on Leaf - PhoneC):**
    *   The `onConnectionResult` callback is triggered on PhoneC with `STATUS_OK`.
    *   PhoneC stores the endpoint ID for PhoneB (`lieutenantEndpointId`).
12. **Ready State:** PhoneC is now a Leaf, connected to the Lieutenant PhoneB.

## Flow 4: General Broadcasts a Display Message

1.  **User Action (on General - PhoneA):** The user taps a display button in the `WebView` UI.
2.  **JavaScript Bridge:** The button click calls a JavaScript function that invokes the native Kotlin `JavaScriptInjectedAndroid.broadcastDisplayMessage(target)` method.
3.  **Create Payload:**
    *   A `NetworkMessage` is created with the `displayTarget` field set.
    *   This object is serialized into a `Payload` of type `Payload.Type.BYTES`.
4.  **Send to Lieutenants:**
    *   The General's `NearbyConnectionsManager` calls `sendPayload()`, passing the payload to the list of all connected `lieutenantEndpointIds` (which includes PhoneB).
5.  **Payload Received (on Lieutenant - PhoneB):**
    *   The `PayloadCallback.onPayloadReceived` is triggered on PhoneB.
    *   The incoming payload is deserialized back into a `NetworkMessage`.
    *   The code checks that the sender was the `mainHubEndpointId`.
6.  **Forward to Leafs:**
    *   Since the message is from the Hub, the Lieutenant's logic immediately forwards it.
    *   `NearbyConnectionsManager` calls `sendPayload()`, passing the *exact same payload* to its list of `clientEndpointIds` (which includes PhoneC).
7.  **Payload Received (on Leaf - PhoneC):**
    *   The `PayloadCallback.onPayloadReceived` is triggered on PhoneC.
    *   The payload is deserialized into a `NetworkMessage`.
    *   The app logic identifies it as a "display" message.
8.  **Display Update:**
    *   The message is passed to the `WebAppActivity` via its static reference.
    *   The activity uses `runOnUiThread` to execute a JavaScript call in the `WebView`, updating the UI to show the chosen display.
9.  **Simultaneous Display (on Lieutenant - PhoneB):** The Lieutenant, upon receiving the message from the General, will *also* perform the same UI update for its own screen, in parallel with forwarding the message.
