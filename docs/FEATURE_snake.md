# Self-Healing, Randomized Mesh Topology Plan

This document outlines the plan to create a fully connected and resilient P2P mesh from devices that
support a flexible number of connections (2-4), designed for a dynamic and unstable environment.

## 1. Introduction

The primary goal is to create a peer-to-peer network that is robust against frequent node failures
and can quickly heal itself to prevent network partitioning (islands). This topology is optimized
for broadcasting small messages to all nodes in the network.

## 2. Core Concepts

The "Self-Healing, Randomized Mesh" is built on the following principles:

- **Redundancy:** Each node will aim to maintain a flexible number of connections (2-4) to ensure
  multiple paths for data propagation. (See `MIN_CONNECTIONS` and `MAX_CONNECTIONS` in [
  `NearbyConnectionsManager.kt`](../app/src/main/java/info/benjaminhill/localmesh2/NearbyConnectionsManager.kt))
- **Randomization:** To avoid suboptimal network topologies, nodes will periodically and randomly
  drop a connection to a well-connected peer and seek a new, less-connected peer. (See
  `startReshuffling()` in [
  `NearbyConnectionsManager.kt`](../app/src/main/java/info/benjaminhill/localmesh2/NearbyConnectionsManager.kt))
- **Dynamic Adaptation:** The network will constantly adapt to nodes joining and leaving, with a
  focus on quick recovery from failures. (See `onDisconnected()` in [
  `NearbyConnectionsManager.kt`](../app/src/main/java/info/benjaminhill/localmesh2/NearbyConnectionsManager.kt))
- **Decentralization:** There is no central coordinator; all nodes operate independently.

## 3. Connection Management

The [
`NearbyConnectionsManager.kt`](../app/src/main/java/info/benjaminhill/localmesh2/NearbyConnectionsManager.kt)
is modified to handle the following logic.

### 3.1. State Management

Each device will maintain its state, which includes:

- A list of currently connected endpoints. (See `connectedEndpoints` in [
  `NearbyConnectionsManager.kt`](../app/src/main/java/info/benjaminhill/localmesh2/NearbyConnectionsManager.kt))
- A set of discovered endpoints, along with their advertised metadata (e.g., number of active
  peers). (See `discoveredEndpoints` in [
  `NearbyConnectionsManager.kt`](../app/src/main/java/info/benjaminhill/localmesh2/NearbyConnectionsManager.kt))

### 3.2. Advertising and Discovery

- Devices will continuously advertise their presence using `startAdvertising()`. (See
  `startAdvertising()` in [
  `NearbyConnectionsManager.kt`](../app/src/main/java/info/benjaminhill/localmesh2/NearbyConnectionsManager.kt))
- The advertising payload will be enhanced to include the node's current number of active
  connections. (See `startAdvertising()` in [
  `NearbyConnectionsManager.kt`](../app/src/main/java/info/benjaminhill/localmesh2/NearbyConnectionsManager.kt))
- Devices will continuously discover other devices using `startDiscovery()`. (See `startDiscovery()`
  in [
  `NearbyConnectionsManager.kt`](../app/src/main/java/info/benjaminhill/localmesh2/NearbyConnectionsManager.kt))

### 3.3. Connection Initiation

- In the `onEndpointFound` callback, a device will check if it has less than `MAX_CONNECTIONS`.
- If it has less than `MAX_CONNECTIONS`, it will prioritize connecting to newly discovered endpoints
  that have fewer than `MIN_CONNECTIONS`. (See `endpointDiscoveryCallback` in [
  `NearbyConnectionsManager.kt`](../app/src/main/java/info/benjaminhill/localmesh2/NearbyConnectionsManager.kt))

### 3.4. Connection Acceptance/Rejection

- In the `onConnectionInitiated` callback, a device will check if it has less than
  `MAX_CONNECTIONS`.
- If it has less than `MAX_CONNECTIONS`, it will accept the incoming connection.
- If it already has `MAX_CONNECTIONS`, it will attempt to make room by invoking `findRedundantPeer()` to disconnect from a peer that is safely reachable via a 2-hop path. If successful, it will accept the new connection; otherwise it will be rejected. (See
  `connectionLifecycleCallback` in [
  `NearbyConnectionsManager.kt`](../app/src/main/java/info/benjaminhill/localmesh2/NearbyConnectionsManager.kt))

### 3.5. Handling Disconnections and Network Healing

- In the `onDisconnected` callback, the device will remove the disconnected endpoint from its list
  of connected endpoints.
- After a disconnection, if a node has fewer than `MIN_CONNECTIONS`, it should immediately and
  aggressively try to connect to any available node to prevent becoming an island. (See
  `onDisconnected` in [
  `NearbyConnectionsManager.kt`](../app/src/main/java/info/benjaminhill/localmesh2/NearbyConnectionsManager.kt))

### 3.6. Proactive Re-shuffling

- To prevent the network from settling into a suboptimal, clustered topology, each node will
  periodically perform a "re-shuffle".
- Every few minutes, a node will check its connected peers. If all of its peers have 3 or more
  connections, the node will randomly drop one of its connections. (See `startReshuffling()` in [
  `NearbyConnectionsManager.kt`](../app/src/main/java/info/benjaminhill/localmesh2/NearbyConnectionsManager.kt))

## 4. Data Propagation

- For broadcasting, a simple flooding algorithm will be used. When a node receives a message, it
  will forward it to all of its connected peers. (See `broadcast()` and `payloadCallback` in [
  `NearbyConnectionsManager.kt`](../app/src/main/java/info/benjaminhill/localmesh2/NearbyConnectionsManager.kt))
- To prevent infinite loops and redundant transmissions, each message will have a unique ID. A node
  will keep track of the IDs of the messages it has already seen and will not forward a message it
  has already processed. (See `seenMessageIds` and `payloadCallback` in [
  `NearbyConnectionsManager.kt`](../app/src/main/java/info/benjaminhill/localmesh2/NearbyConnectionsManager.kt))

## 5. Implementation Details in `NearbyConnectionsManager.kt`

### 5.1. Strategy

`Strategy.P2P_CLUSTER` is the correct choice as it supports M-to-N connections, which is ideal for a
mesh network.

### 5.2. Code Modifications

- **[
  `NearbyConnectionsManager.kt`](../app/src/main/java/info/benjaminhill/localmesh2/NearbyConnectionsManager.kt):
  **
    - Update `maxPeers` to be `private const val MAX_CONNECTIONS = 4` and
      `private const val MIN_CONNECTIONS = 2`.
    - Modify the `startAdvertising` call to include the number of connected peers in the advertised
      name.
    - In `onEndpointFound`, parse the number of peers from the discovered endpoint's name and
      implement the connection logic described in section 3.3.
    - In `onConnectionInitiated`, implement the logic to accept or reject the connection based on
      the number of current connections.
    - Implement the "re-shuffling" logic using a periodic timer.

## 6. Future Improvements

- **Signal Strength:** The Nearby Connections API provides some information about the signal
  strength of discovered endpoints. This could be used to prioritize connections to physically
  closer devices, which may be more stable.
- **Congestion Control:** For higher-traffic scenarios, a more sophisticated data propagation
  strategy could be implemented to avoid network congestion.
- **Dynamic `maxPeers`:** The maximum number of connections could be dynamically adjusted based on
  network conditions. For example, in a very stable network, nodes could reduce their number of
  connections to save battery.
