# P2P Network Flow Documentation

This document outlines the step-by-step process of a new node joining an existing mesh network and
the subsequent triggering of a network-wide UI change.

## Phase 1: New Node Joins the Network

**Start State:** There are 7 nodes (Node1-Node7) fully connected to each other in a mesh
network. Each node has 6 active connections.

**End State:** `NewNode` is integrated into the network, and the overall network topology has been
optimized.

---

1. **Initial State:** 7 nodes (Node1-Node7) are fully connected. Each node maintains 6 direct
   connections.

2. **NewNode Startup:** A new node, `NewNode`, starts the application. The
   `NearbyConnectionsManager` is initialized. `NewNode` begins advertising its presence and
   simultaneously discovering other nodes in the vicinity.

3. **First Discovery:** `NewNode`'s discovery callback is triggered when it finds `Node1`. Since
   `NewNode` has no connections, the distance to `Node1` is considered infinite. The condition to
   initiate a connection (`distance > 2`) is met.

4. **Connection Request:** `NewNode` sends a connection request to `Node1`.

5. **Connection Acceptance:** `Node1` receives the request. It currently has 6 connections, which is
   below its hardware limit of 7. Therefore, `Node1` accepts the connection from `NewNode`.

6. **Connection Established:** A direct connection is formed between `NewNode` and `Node1`. The
   `EndpointRegistry` on both nodes is updated to set their distance to each other as 1. `NewNode`
   now has 1 connection, and `Node1` has 7.

7. **Initial Gossip Exchange:** Within 10 seconds, `NewNode` and `Node1` exchange gossip messages.
    * `NewNode` informs `Node1` about its single peer (`Node1`).
    * `Node1` informs `NewNode` about its 7 peers (`Node2` through `Node7`, and `NewNode`).

8. **Registry Update on NewNode:** `NewNode` processes the gossip from `Node1`. It learns about
   `Node2` through `Node7` for the first time and registers them with a distance of 2 (since they
   are 1 hop away from `Node1`).

9. **Concurrent Connections:** While the connection to `Node1` was being established, `NewNode`
   continued discovering other nodes. It proceeds to connect to `Node2` and `Node3` in a similar
   fashion. For the purpose of this flow, we'll assume it establishes these three initial
   connections.

10. **Network State after Initial Connections:**
    * `NewNode`: Connected to `Node1`, `Node2`, `Node3`. (3 connections)
    * `Node1`, `Node2`, `Node3`: Each connected to the original 6 nodes plus `NewNode`. (7
      connections each)
    * `Node4`, `Node5`, `Node6`, `Node7`: Remain connected to the original 6 nodes. (6 connections
      each)
    * Through gossip, `Node4-7` learn about `NewNode` at a distance of 2.

11. **Network Reshuffling (`Node4`'s Turn):** The `reshuffle()` function runs periodically on all
    nodes to optimize the network topology. After about 30 seconds, `Node4` runs this logic:
    * **`findWorstDistantNode()`**: `Node4` checks its registry of known endpoints. `NewNode` is
      known with a distance of 2. Assuming all other nodes are at distance 1, `NewNode` is selected
      as the "worst distant node" to connect to.
    * **`findRedundantPeer()`**: `Node4` examines its direct peers. `Node1`, `Node2`, and `Node3`
      each have 7 connections, while `Node5`, `Node6`, and `Node7` have 6. `Node4` will choose the
      peer with the most connections as redundant, in this case, `Node1`.
    * **Decision:** `Node4` decides to improve network topology. It disconnects from the redundant
      peer (`Node1`) and initiates a connection with the worst distant node (`NewNode`).

12. **Connection Shift:**
    * `Node4` disconnects from `Node1`. `Node4`'s connection count drops to 5; `Node1`'s drops to 6.
    * `Node4` connects to `NewNode`. `Node4`'s connection count is now 6; `NewNode`'s is now 4.

13. **Continuing Optimization:** This reshuffling process continues across the entire network. Nodes
    with a high number of connections will gradually shed them in favor of connecting to more
    distant nodes. This balances the connection load, reduces the average distance between any two
    nodes, and ensures `NewNode` becomes a well-integrated member of the mesh.

## Phase 2: "Disco" Visualization

**Start Situation:** `NewNode` is connected and integrated into the mesh.

**End State:** `NewNode` is showing the "disco" visualization.

---

14. **User Action:** A user on `Node1` opens the Web UI. The UI lists available visualizations,
    including "disco". The user clicks "disco" after ensuring the "Render Locally" checkbox is
    *unchecked*.

15. **JavaScript Interface Call:** The `main.js` script running on `Node1`'s device executes
    `Android.sendPeerDisplayCommand("disco")`.

16. **Broadcast Initiated:** The `JavaScriptInjectedAndroid` class on `Node1` receives this call. It
    constructs a `NetworkMessage` of type `DISPLAY` with the content "disco" and passes it to the
    `NearbyConnectionsManager` to be broadcast.

17. **Message Flooding:** `Node1` sends this message to all of its direct peers (e.g., `NewNode`,
    `Node2`, etc.).
    * The message contains a unique ID, which is immediately stored in the `seenMessageIds` map on
      `Node1` to prevent processing duplicate messages.

18. **First Hop Reception (`NewNode`):**
    * `NewNode` receives the `DISPLAY` message from `Node1`.
    * It checks the message ID against its `seenMessageIds` map. The ID is new, so it proceeds.
    * The `payloadCallback` identifies the message type as `DISPLAY` and calls
      `WebAppActivity.navigateTo("disco")`.
    * The `WebAppActivity` on `NewNode` receives this command and, executing on the main UI thread,
      updates the `FullScreenWebView`'s URL to `file:///android_asset/disco/index.html`.
    * **End State Reached:** `NewNode` is now displaying the "disco" visualization.

19. **Message Re-broadcast:**
    * After processing the message, `NewNode` re-broadcasts the *exact same message* to all of its
      peers except for `Node1` (the node it received the message from). The message's hop count is
      incremented.

20. **Subsequent Hops & Loop Prevention:**
    * Other nodes (like `Node2`) also receive, process, and re-broadcast the message.
    * If a node receives a message with an ID that is already in its `seenMessageIds` map, it
      ignores the message completely, preventing infinite loops.
    * This flooding mechanism ensures the command propagates rapidly and efficiently throughout the
      entire mesh network.
