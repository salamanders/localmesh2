# P2P Network Flow Documentation

This document outlines the step-by-step process of a new node joining a mesh
network using the "Mandate-and-Verify" (or "SlowConnect") algorithm.

## Phase 1: New node joins the network

**Start State:** An existing mesh network of any size is running.

**End State:** `NewNode` is integrated into the network with one or two connections,
and the network remains a single, healthy island.

---

1.  **NewNode Startup:** A new node, `NewNode`, starts the application. The
    `NearbyConnectionsManager` is initialized, which in turn initializes the `TopologyOptimizer`.
    `NewNode` begins discovering other nodes in the vicinity.

2.  **Discovery:** `NewNode`'s `endpointDiscoveryCallback` in `NearbyConnectionsManager` is
    triggered for each nearby node. These discovered endpoints are added to a set of
    `availablePeers` in the `TopologyOptimizer`.

3.  **Mandatory First Connection:**
    *   The `TopologyOptimizer`'s `ensureConnectionsJob` continuously monitors the number of
        active connections. Seeing it has none, it picks a random node from its `availablePeers`
        list, `OtherNodeA`, and requests a connection.
    *   If the connection to `OtherNodeA` succeeds, `NewNode` now has one connection.
    *   If the connection fails (e.g., `OtherNodeA` just reached its own connection limit in a
        race condition), the `TopologyOptimizer` will simply pick another peer from the
        `availablePeers` list on its next cycle and try again. This process repeats until one
        connection is successfully established, guaranteeing `NewNode` joins the network.

4.  **Best-Effort Second Connection:**
    *   On the next cycle of the `ensureConnectionsJob`, the `TopologyOptimizer` sees that
        `NewNode` has one connection and attempts to establish a second for robustness.
    *   It picks another random, unconnected peer, `OtherNodeB`, from the `availablePeers` list and
        attempts a connection.
    *   If this succeeds, `NewNode` now has two connections.
    *   If it fails, or if there are no other available peers, the `TopologyOptimizer` will wait
        for a randomized period (30-45 seconds) and then check again. This "best-effort"
        approach continues indefinitely.

5.  **Receiving Connections:**
    *   `NewNode` will also advertise its own availability.
    *   When an incoming connection request is received, the `onConnectionInitiated` callback in
        `NearbyConnectionsManager` checks if the node is already at its connection limit (5).
    *   If it has fewer than 5 connections, it accepts the new connection.
    *   If it already has 5 connections, it rejects the request, preventing oversaturation.

## Phase 2: Gossip and Network Stability

With the removal of the complex `reshuffle` logic, the network stabilizes organically. The periodic
`purgeJob` in the `TopologyOptimizer` is still active, removing endpoints that haven't been seen in
a while, which helps keep the network view clean. While the explicit `gossipJob` has been removed,
gossip can be re-introduced as a separate, simplified mechanism if needed in the future. The primary
focus of the "SlowConnect" algorithm is to ensure connectivity and prevent network partitions with
minimal overhead.

## Phase 3: User-initiated Display Command

This phase remains largely unchanged from the previous architecture. A user action triggers a
`DISPLAY` message, which floods through the network.

1.  **User Action:** A user on any node triggers a `sendPeerDisplayCommand`.

2.  **Broadcast:** The `NearbyConnectionsManager` constructs and broadcasts a `DISPLAY` type
    `NetworkMessage`.

3.  **Message Flooding:** The message is sent to all direct peers. Each receiving node processes the
    command (updating its UI) and then re-broadcasts the message to its own peers (except the
    original sender). A `seenMessageIds` map prevents infinite loops. This ensures the command
    propagates rapidly throughout the entire mesh.
