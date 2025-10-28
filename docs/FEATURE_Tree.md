# **Checklist: 1-to-30 Nearby Connections Broadcast Network**

## **1. Project Goal**

To create a system where one "Main Hub" phone can broadcast a message (a Payload) to 30 "Client"
phones simultaneously, without a Wi-Fi router.

## **2. Core Concept: The "Tree" Topology**

We cannot connect all 30 phones to the Main Hub directly. A single phone's Wi-Fi hotspot (which
P2P_STAR uses) has a hardware limit of about 7 connections. The older phones may have less, around a
max of 5.

**Solution:** We will create a multi-level "tree" structure.

* **Level 0:** The **Main Hub** (1 phone)
* **Level 1:** The **Lieutenants** (4-5 phones) connect to the Main Hub.
* **Level 2:** The **Clients** (25-26 phones) connect to the Lieutenants.

**Message Flow:** Main Hub -> Lieutenants -> Clients.

To manage this, we will use **two different serviceId strings**:

1. HUB_SERVICE_ID (e.g., "localmesh2-hub-level"): Used for Level 0-1 connection.
2. CLIENT_SERVICE_ID (e.g., "localmesh2-client-level"): Used for Level 1-2 connection.

## **3. General Checklist (For ALL Roles)**

* [ ] Add all required permissions to AndroidManifest.xml. This is likely unchanged.
    * BLUETOOTH_S (if targeting Android 12+)
    * BLUETOOTH_ADVERTISE (if targeting Android 12+)
    * BLUETOOTH_CONNECT (if targeting Android 12+)
    * BLUETOOTH (maxSdkVersion 30)
    * BLUETOOTH_ADMIN (maxSdkVersion 30)
    * ACCESS_WIFI_STATE
    * CHANGE_WIFI_STATE
    * ACCESS_COARSE_LOCATION (maxSdkVersion 28)
    * ACCESS_FINE_LOCATION
* [ ] Implement runtime permission checks for ACCESS_FINE_LOCATION and (on Android 12+). This is
  likely unchanged.
  BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT, and BLUETOOTH_SCAN.
* [ ] Get an instance of Nearby.getConnectionsClient().
* [ ] Set the Strategy to Strategy.P2P_STAR for all advertising and discovery.
* [ ] Create a single, shared PayloadCallback for all roles.
    * [ ] In onPayloadReceived(), log the payload. The specific logic for each role is detailed
      below.
    * [ ] In onPayloadTransferUpdate(), log the status (e.g., SUCCESS, FAILURE).

## **4. Role 1: Main Hub (1 Phone)**

This phone is your "remote control." It only advertises and sends the initial message.

This means the MainActivity will need a modification:

1. The app starts up.
2. It shows a button: "Become Remote Control"
3. It also waits for 10 seconds. If the button isn't pressed in 10 seconds, it continues normally (
   See below for how it decides to be a Lieutenant or a leaf)
4. If the button IS pressed, this phone is the hub. Do the following:


* [ ] **Constants & Variables:**
    * [ ] private final String SERVICE_ID = "localmesh2-hub-level";
    * [ ] private final int MAX_LIEUTENANTS = 5; (5 is a safe number).
    * [ ] private final List<String> lieutenantEndpointIds = new ArrayList<>();
* [ ] **Setup:**
    * [ ] Call startAdvertising():
        * serviceId: SERVICE_ID
        * strategy: Strategy.P2P_STAR
        * ConnectionLifecycleCallback: Use the one defined below.
* [ ] **Connection Logic (ConnectionLifecycleCallback):**
    * [ ] **onConnectionInitiated(String endpointId, ConnectionInfo connectionInfo):**
        * [ ] Check if (lieutenantEndpointIds.size() >= MAX_LIEUTENANTS).
        * [ ] **If TRUE (we are full):** Call Nearby.getConnectionsClient(this).rejectConnection(
          endpointId).
        * [ ] **If FALSE (we have space):** Call Nearby.getConnectionsClient(this).acceptConnection(
          endpointId, payloadCallback).
    * [ ] **onConnectionResult(String endpointId, ConnectionResolution result):**
        * [ ] Check if (result.getStatus().getStatusCode() == ConnectionsStatusCodes.STATUS_OK).
        * [ ] **If TRUE (connected):** Add the ID: lieutenantEndpointIds.add(endpointId).
        * [ ] **If FALSE (failed):** Log the error.
* [ ] **Disconnection Logic:**
    * [ ] **onDisconnected(String endpointId):**
        * [ ] Remove the ID: lieutenantEndpointIds.remove(endpointId).
        * *(This is critical. It opens a slot for a new Lieutenant if one disconnects, making the
          network self-healing.)*
* [ ] **Payload Logic:**
    * [ ] **Show the menu of visualization folders (same as current). When a button is clicked:**
        * [ ] Create your Payload: A "Display" network message (same as current)
        * [ ] Send to all Lieutenants: Nearby.getConnectionsClient(this).sendPayload(
          lieutenantEndpointIds, payload).
    * [ ] **onPayloadReceived(String endpointId, Payload payload):**
        * *(This phone shouldn't receive payloads, add a log here just in case.)*

## **5. Role 2: Lieutenant (5 Phones)**

This is the most complex role. It acts as a **Discoverer** (to find the Hub) and an **Advertiser** (
to be found by Clients).

* [ ] **Constants & Variables:**
    * [ ] private final String HUB_SERVICE_ID = "localmesh2-hub-level";
    * [ ] private final String CLIENT_SERVICE_ID = "localmesh2-client-level";
    * [ ] private final int MAX_CLIENTS_PER_LIEUTENANT = 6;
    * [ ] private final List<String> clientEndpointIds = new ArrayList<>();
    * [ ] private String mainHubEndpointId = null;
* [ ] **Setup:**
    * [ ] **Start Advertising (for Clients):**
        * [ ] Call startAdvertising():
            * serviceId: CLIENT_SERVICE_ID
            * strategy: Strategy.P2P_STAR
            * ConnectionLifecycleCallback: Use clientLifecycleCallback (defined below).
    * [ ] **Start Discovery (for Hub):**
        * [ ] Call startDiscovery():
            * serviceId: HUB_SERVICE_ID
            * strategy: Strategy.P2P_STAR
            * EndpointDiscoveryCallback: Use hubDiscoveryCallback (defined below).
* [ ] **Discovery Logic (hubDiscoveryCallback):**
    * [ ] **onEndpointFound(String endpointId, DiscoveredEndpointInfo info):**
        * [ ] Check if (mainHubEndpointId == null).
        * [ ] **If TRUE (we haven't connected to a hub yet):**
            * [ ] Call Nearby.getConnectionsClient(this).requestConnection(endpointId,
              hubLifecycleCallback).
            * *(No need to stop discovery. requestConnection will handle it.)*
    * [ ] **onEndpointLost(String endpointId):**
        * *(Log that the Hub was lost.)*
* [ ] **Connection Logic (to Hub - hubLifecycleCallback):**
    * [ ] **onConnectionInitiated(String endpointId, ConnectionInfo connectionInfo):**
        * [ ] Automatically accept the Hub: Nearby.getConnectionsClient(this).acceptConnection(
          endpointId, payloadCallback).
    * [ ] **onConnectionResult(String endpointId, ConnectionResolution result):**
        * [ ] Check if (result.getStatus().getStatusCode() == ConnectionsStatusCodes.STATUS_OK).
        * [ ] **If TRUE:** Store the ID: mainHubEndpointId = endpointId;.
        * [ ] **If FALSE:** Reset: mainHubEndpointId = null; (and this is where you get demoted to a
          LEAF, see below)
* [ ] **Connection Logic (to Clients - clientLifecycleCallback):**
    * [ ] **onConnectionInitiated(String endpointId, ConnectionInfo connectionInfo):**
        * [ ] Check if (clientEndpointIds.size() >= MAX_CLIENTS_PER_LIEUTENANT).
        * [ ] **If TRUE (we are full):** Call Nearby.getConnectionsClient(this).rejectConnection(
          endpointId).
        * [ ] **If FALSE (we have space):** Call Nearby.getConnectionsClient(this).acceptConnection(
          endpointId, payloadCallback).
    * [ ] **onConnectionResult(String endpointId, ConnectionResolution result):**
        * [ ] Check if (result.getStatus().getStatusCode() == ConnectionsStatusCodes.STATUS_OK).
        * [ ] **If TRUE:** Add to list: clientEndpointIds.add(endpointId).
* [ ] **Disconnection Logic:**
    * [ ] **onDisconnected(String endpointId):**
        * [ ] Check if (endpointId.equals(mainHubEndpointId)).
        * [ ] **If TRUE (Hub disconnected):**
            * [ ] mainHubEndpointId = null;
            * [ ] **Crucial:** Call startDiscovery() again (using HUB_SERVICE_ID) to find a new Hub.
        * [ ] **If FALSE (Client disconnected):**
            * [ ] clientEndpointIds.remove(endpointId);
* [ ] **Payload Logic:**
    * [ ] **onPayloadReceived(String endpointId, Payload payload):**
        * [ ] **Crucial:** Check if (endpointId.equals(mainHubEndpointId)).
        * [ ] **If TRUE (message is from Hub):**
            * [ ] **Forward the message:** Nearby.getConnectionsClient(this).sendPayload(
              clientEndpointIds, payload).

## **6. Role 3: Client/LEAF (~25 Phones)**

This is the simplest role. It only discovers and receives. A phone that tried to connect to the Hub
but was rejected becomes a LEAF.

* [ ] **Constants & Variables:**
    * [ ] private final String SERVICE_ID = "localmesh2-client-level";
    * [ ] private String lieutenantEndpointId = null;
* [ ] **Setup:**
    * [ ] Call startDiscovery():
        * serviceId: SERVICE_ID
        * strategy: Strategy.P2P_STAR
        * EndpointDiscoveryCallback: Use discoveryCallback (defined below).
* [ ] **Discovery Logic (discoveryCallback):**
    * [ ] **onEndpointFound(String endpointId, DiscoveredEndpointInfo info):**
        * [ ] Check if (lieutenantEndpointId == null).
        * [ ] **If TRUE (we are not connected):**
            * [ ] Call Nearby.getConnectionsClient(this).requestConnection(endpointId,
              connectionLifecycleCallback).
    * [ ] **onEndpointLost(String endpointId):**
        * *(Log that a Lieutenant was lost.)*
* [ ] **Connection Logic (connectionLifecycleCallback):**
    * [ ] **onConnectionInitiated(String endpointId, ConnectionInfo connectionInfo):**
        * [ ] Automatically accept the Lieutenant: Nearby.getConnectionsClient(this)
          .acceptConnection(endpointId, payloadCallback).
    * [ ] **onConnectionResult(String endpointId, ConnectionResolution result):**
        * [ ] Check if (result.getStatus().getStatusCode() == ConnectionsStatusCodes.STATUS_OK).
        * [ ] **If TRUE:** Store the ID: lieutenantEndpointId = endpointId;.
        * [ ] **If FALSE:** Reset: lieutenantEndpointId = null;.
* [ ] **Disconnection Logic:**
    * [ ] **onDisconnected(String endpointId):**
        * [ ] Reset: lieutenantEndpointId = null;.
        * [ ] **Crucial:** Call startDiscovery() again to find a new Lieutenant. You never get
          promoted back to being a Lieutenant.
* [ ] **Payload Logic:**
    * [ ] **onPayloadReceived(String endpointId, Payload payload):**
        * [ ] This is the final destination.
        * [ ] **Perform the action** (e.g., flash a light, play a sound, etc.). No need to forward
          the message on.