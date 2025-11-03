# New Node Joining and Receiving a Display Message

This document outlines the sequence of events when a new node joins the mesh network and receives a
command to display a visualization.

## 1. Initialization

1. **`MainActivity` Launch:** The user launches the application. `MainActivity` checks for the
   necessary permissions (Bluetooth, Wi-Fi, etc.).
2. **Permissions Grant:** The user grants the permissions.
3. **Network Startup:** `MainActivity` creates an instance of `HealingMeshConnection` and calls
   `startNetworking()`.

## 2. Network Discovery and Connection

4. **Advertising:** `HealingMeshConnection` starts advertising its presence on the network using the
   Google Nearby Connections API. It advertises its unique `localHumanReadableName` (e.g., "abcde").
5. **Discovery:** Simultaneously, `HealingMeshConnection` starts discovering other nearby devices
   that are advertising the same `MESH_SERVICE_ID`.
6. **`onEndpointFound`:** When an existing node (let's call it "Node A") discovers the new node (
   let's call it "Node B"), the `onEndpointFound` callback is triggered on Node A.
7. **Endpoint Registry:** Node A adds Node B to its `EndpointRegistry`, which tracks all known
   nodes.
8. **Connection Healing:** The `maintenanceTicker` in `HealingMeshConnection` runs periodically. Its
   `healConnections` method checks if the number of direct connections is below `MIN_CONNECTIONS`.
9. **Connection Request:** Since the new node (Node B) has 0 connections, it sees Node A as a
   potential connection. Node B calls `connectionsClient.requestConnection()` to initiate a
   connection to Node A.
10. **`onConnectionInitiated`:** The `onConnectionInitiated` callback is triggered on Node A.
11. **Connection Acceptance:** Node A checks if it has fewer than `MAX_CONNECTIONS`. If so, it calls
    `connectionsClient.acceptConnection()`.
12. **`onConnectionResult`:** The `onConnectionResult` callback is triggered on both nodes. If the
    connection is successful, both nodes mark each other as direct peers in their respective
    `EndpointRegistry` by setting the distance to 1. The new node is now part of the mesh.

## 3. Gossip and Network Convergence

13. **Gossip Protocol:** The `gossipTicker` on all nodes periodically triggers the `sendGossip`
    function.
14. **Message Creation:** A node creates a `NetworkMessage` containing its own ID and a timestamp in
    the `breadCrumbs` list.
15. **Forwarding:** The node forwards this message to all its direct peers.
16. **Breadcrumb Appending:** When a peer receives the gossip message, it appends its own ID and the
    current timestamp to the `breadCrumbs` list.
17. **Re-forwarding:** The peer then forwards the updated message to all of its *other* peers (
    excluding the one it just received the message from). This prevents the message from immediately
    going back where it came from.
18. **Network Mapping:** As messages propagate, the `processBreadcrumbs` function is called on each
    receiving node. This function updates the local `EndpointRegistry` with the distance (in hops,
    derived from the `breadCrumbs` list length) to other nodes. This is how a node learns about
    peers that are not directly connected to it.
19. **Convergence:** Over time, through this gossip mechanism, the entire network topology
    converges, and every node has a reasonably up-to-date map of the entire mesh.

## 4. Receiving a Display Message

20. **User Action:** A user on any device in the mesh clicks a button in the web UI to display a
    visualization on another node.
21. **JavaScript Bridge:** The `JavaScriptInjectedAndroid.sendPeerDisplayCommand(folder)` function
    is called.
22. **Broadcast:** This function creates a new `NetworkMessage`, setting the `displayTarget` to the
    name of the folder containing the visualization. It then calls
    `HealingMeshConnection.broadcast(message)`.
23. **Message Propagation:** The `broadcast` function forwards the message to all its direct peers.
    These peers then forward it to *their* peers, and so on, until the message has reached every
    node in the network. The same de-duplication and loop-prevention logic from the gossip protocol
    applies here.
24. **Command Execution:** When a node receives the message, the `payloadCallback` checks if the
    `displayTarget` matches its own `localHumanReadableName`.
25. **Display Update:** If the names match, the node would then launch the `WebAppActivity` with the
    specified folder path, causing the `WebView` to load the new visualization. (Note: The `TODO` in
    the code indicates this final step of command execution is not yet fully implemented).
