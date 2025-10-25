# Snake Topology P2P Mesh Plan

This document outlines the plan to create a fully connected P2P mesh from devices that only support
two connections each, forming a "snake" or ring topology.

## 1. Introduction

The goal is to create a peer-to-peer network where each device (node) connects to a maximum of two
other devices. This will form a linear chain of devices, and when the ends of the chain connect, it
forms a ring. This topology is efficient for data propagation in a specific order and can be used
for various applications, such as distributed data processing, content sharing, and collaborative
applications.

## 2. Connection Management

The core of the snake topology is careful management of connections. The
`NearbyConnectionsManager.kt` will be modified to handle the following logic.

### 2.1. State Management

Each device needs to maintain its state, which includes:

- A list of currently connected endpoints (max 2).
- A set of discovered endpoints.
- A set of endpoints with pending connection requests.

### 2.2. Discovery

- Devices will continuously advertise their presence using `startAdvertising()`.
- Devices will continuously discover other devices using `startDiscovery()`.

### 2.3. Connection Initiation

- In the `onEndpointFound` callback, a device will check if it has less than two connections.
- If it has less than two connections, it will attempt to connect to the discovered endpoint.
- To avoid two devices trying to connect to each other at the same time, we can introduce a simple
  rule: the device with the lexicographically smaller endpoint ID initiates the connection.

### 2.4. Connection Acceptance/Rejection

- In the `onConnectionInitiated` callback, a device will check if it has less than two connections.
- If it has less than two connections, it will accept the incoming connection.
- If it already has two connections, it will reject the incoming connection.

### 2.5. Handling Disconnections

- In the `onDisconnected` callback, the device will remove the disconnected endpoint from its list
  of connected endpoints.
- After a disconnection, the device will have less than two connections, so it will be eligible to
  connect to new devices discovered in `onEndpointFound`.

## 3. Data Propagation

- Data will be propagated along the snake. When a device receives a message, it will forward it to
  its other connected peer.
- To prevent infinite loops in the ring, each message should have a unique ID. A device will keep
  track of the IDs of the messages it has already seen and will not forward a message it has already
  processed.

## 4. Implementation Details in `NearbyConnectionsManager.kt`

### 4.1. Strategy

While `Strategy.P2P_CLUSTER` can be used with manual connection management, `Strategy.P2P_STAR`
might be a better fit. With `P2P_STAR`, one device acts as a hub. We can adapt this by having each
device act as a hub for at most one other device. However, for a more decentralized approach, we
will stick with `P2P_CLUSTER` and implement the connection management logic ourselves.

### 4.2. Code Modifications

- **`NearbyConnectionsManager.kt`:**
    - Add a `private val connectedEndpoints = mutableSetOf<String>()` to track connected endpoints.
    - In `onEndpointFound`, add the logic to initiate a connection if `connectedEndpoints.size < 2`.
    - In `onConnectionInitiated`, add the logic to accept or reject the connection based on
      `connectedEndpoints.size`.
    - In `onConnectionResult`, if the connection is successful, add the endpoint to
      `connectedEndpoints`.
    - In `onDisconnected`, remove the endpoint from `connectedEndpoints`.

## 5. Future Improvements

- **Network Healing:** If the snake breaks (a node in the middle disconnects), the two ends of the
  break will have only one connection. They will then be able to discover and connect to each other,
  healing the snake.
- **Multiple Snakes:** The current plan assumes a single snake. If the network is partitioned,
  multiple snakes could form. We could introduce a mechanism to merge snakes when they discover each
  other.
