# P2P Network Flow Documentation

This document outlines the step-by-step process of a new node joining a fully connected mesh
network, the subsequent gossip synchronization, and the triggering of a network-wide UI change.

## Phase 1: New node joins an already full network

**Start State:** There are 8 nodes (Node1-Node8) fully connected to each other in a mesh network.
Each node has 7 active connections, which is its maximum capacity. #QA:OK

**End State:** `NewNode` is integrated into the network, and the overall network topology has been
optimized.

---

1. **Initial State:** 8 nodes (Node1-Node8) are fully connected. Each node maintains 7 direct
   connections, meaning its connection slots are full. #QA:OK

2. **NewNode Startup:** A new node, `NewNode`, starts the application. The
   `NearbyConnectionsManager` is initialized. `NewNode` begins advertising its presence and
   simultaneously discovering other nodes in the vicinity. #QA:OK

3. **First Discovery:** `NewNode`'s discovery callback is triggered when it finds `Node1`. Since
   `NewNode` has no connections, the distance to `Node1` is considered infinite. The condition to
   initiate a connection (`distance > 2`) is met. #QA:OK

4. **Connection Request to a Full Node:** `NewNode` sends a connection request to `Node1`. #QA:OK

5. **`findRedundantPeer` Triggered:** `Node1` receives the request but already has 7 connections (
   its limit). Instead of rejecting the connection, it invokes the `findRedundantPeer()` function to
   make room. It examines its directly connected peers and finds that they are all at a distance of
    1. It will select one of them to disconnect from. For this example, we'll assume it chooses to
       disconnect from `Node8`. #QA:OK

6. **Connection Shift:**
    * `Node1` disconnects from `Node8`. `Node1`'s connection count drops to 6; `Node8`'s also drops
      to 6. #QA:OK
    * `Node1` accepts the incoming connection from `NewNode`. `Node1`'s connection count returns to
      7; `NewNode`'s is now 1. #QA:OK

7. **Concurrent Connection Attempts & Race Condition:**
    * While the connection to `Node1` was being established, `NewNode` continued discovering other
      nodes. It may attempt to connect to `Node2` and `Node3` simultaneously. #QA:OK
    * `Node2` and `Node3` will undergo the same `findRedundantPeer` process. This introduces a
      potential race condition. For example, `Node2` might decide to drop its connection to `Node1`
      at the same time `Node1` is dropping its connection to `Node8`. This documentation highlights
      this as a potential area for further investigation to ensure network stability. #QA:OK

8. **Network State after Initial Connections:**
    * `NewNode`: Connected to `Node1`, `Node2`, and `Node3`. (3 connections) #QA:OK
    * `Node1`, `Node2`, `Node3`: Each has dropped one of their original peers to connect to
      `NewNode`. (7 connections each) #QA:OK
    * The nodes that were dropped (e.g., `Node8`) now have fewer connections and will use their own
      `reshuffle()` logic to find and connect to `NewNode`, further integrating it into the mesh.
      #QA:OK

## Phase 2: Gossip messages reach a steady state

**Start State:** `NewNode` has established its initial connections (to `Node1`, `Node2`, `Node3`)
into the mesh. The network topology is temporarily unstable due to the recent connection shifts.
#QA:OK

**End State:** All nodes in the network have a complete and accurate map of the network topology in
their respective `EndpointRegistry`. #QA:OK

---

1. **Initial Gossip Exchange:** Within 10 seconds of connecting, `NewNode` and its direct peers (
   `Node1`, `Node2`, `Node3`) begin exchanging gossip messages. #QA:OK
    * `NewNode` informs its peers about its own connections. #QA:OK
    * `Node1`, `Node2`, and `Node3` each send their full list of peers to `NewNode`. #QA:OK

2. **Registry Update on `NewNode`:** `NewNode` processes the gossip from its peers.
    * It learns about `Node4` through `Node8` for the first time. #QA:OK
    * It registers these new nodes with a distance of 2 (since they are 1 hop away from its direct
      connections). #QA:OK
    * Its `EndpointRegistry` now contains all 9 nodes in the network. #QA:OK

3. **Registry Update on the Main Network:**
    * `Node1`, `Node2`, and `Node3` update their registries to show `NewNode` at a distance of 1.
      #QA:OK
    * This information propagates rapidly through the network. When a node receives a `GOSSIP`
      message, it not only processes it but also immediately re-broadcasts it to its other peers.
      This ensures that topology changes spread much faster than waiting for the next periodic
      gossip cycle. For instance, when `Node1` gossips with `Node4`, `Node4` learns about `NewNode`
      and registers it at a distance of 2. #QA:OK

4. **Reaching a Steady State:** After a few cycles of the periodic gossip exchange (typically
   within 30-60 seconds), the information about `NewNode` and the other connection changes will
   have propagated throughout the entire network. At this point, every node has a complete and
   consistent view of the 9-node network topology. This stable state is crucial for the
   `reshuffle()` logic on each node to make informed decisions about optimizing connections. #QA:OK

## Phase 3: User-initiated Display Command

**Start Situation:** `NewNode` is fully connected and integrated into the 9-node mesh. All nodes
have a stable view of the network topology. #QA:OK

**End State:** All 9 nodes in the network are showing the "disco" visualization. #QA:OK

---

1. **User Action:** A user on `Node1` opens the Web UI. The UI lists available visualizations,
   including "disco". The user clicks "disco" after ensuring the "Render Locally" checkbox is
   *unchecked*. #QA:OK

2. **JavaScript Interface Call:** The `main.js` script running on `Node1`'s device executes
   `Android.sendPeerDisplayCommand("disco")`. #QA:OK

3. **Broadcast Initiated:** The `JavaScriptInjectedAndroid` class on `Node1` receives this call. It
   constructs a `NetworkMessage` of type `DISPLAY` with the content "disco" and passes it to the
   `NearbyConnectionsManager` to be broadcast. #QA:OK

4. **Message Flooding:** `Node1` sends this message to all of its direct peers (e.g., `NewNode`,
   `Node2`, etc.). #QA:OK
    * The message contains a unique ID, which is immediately stored in the `seenMessageIds` map on
      `Node1` to prevent processing duplicate messages. #QA:OK

5. **First Hop Reception & Processing (`NewNode`):**
    * `NewNode` receives the message from `Node1`. The `onPayloadReceived` callback in
      `NearbyConnectionsManager` is triggered. #QA:OK
    * Inside this callback, the code first checks the message ID against its `seenMessageIds` map. The ID
      is new, so it is added to the map and the message is processed. #QA:OK
    * The code then inspects the `messageType`. In this case, it's a `DISPLAY` message, so the
      `WebAppActivity.navigateTo("disco")` function is called. If it had been a `GOSSIP` message,
      the `EndpointRegistry` would have been updated with the new topology information. #QA:OK
    * The `WebAppActivity` on `NewNode` receives this command and, executing on the main UI thread,
      updates the `FullScreenWebView`'s URL to `file:///android_asset/disco/index.html`. #QA:OK
    * **End State Reached for one node:** `NewNode` is now displaying the "disco" visualization.
      #QA:OK

6. **Universal Message Re-broadcast:**
    * After processing the payload, the `onPayloadReceived` function unconditionally re-broadcasts the
      message (with an incremented hop count) to all of its peers except for the original sender
      (`Node1`). #QA:OK
    * This logic is now universal for all message types, ensuring both `DISPLAY` commands and `GOSSIP`
      updates propagate through the network using the same efficient flooding mechanism. #QA:OK

7. **Subsequent Hops & Loop Prevention:**
    * Other nodes (like `Node2`, `Node4`, etc.) also receive, process, and re-broadcast the message
      in the same manner. #QA:OK
    * If a node receives a message with an ID that is already in its `seenMessageIds` map, it
      ignores the message completely, preventing infinite loops. #QA:OK
    * This flooding mechanism ensures the command propagates rapidly and efficiently throughout the
      entire 9-node mesh network until all nodes are displaying the visualization. The
      `onPayloadTransferUpdate` is used to monitor the progress of the byte transfer for the
      payload, but the core logic for acting on the message resides in `onPayloadReceived`. #QA:OK
