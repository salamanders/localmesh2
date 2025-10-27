# Plan to Get Mesh Networking Working Quickly

This document outlines the shortest path to a functional mesh network.

## Step 1: Implement Gossip Messaging

* **Goal:** Periodically broadcast the node's status to its immediate peers.
* **Mechanism:**
    - [x] In `NearbyConnectionsManager`, create a recurring job that sends a `GOSSIP`
      `NetworkMessage` to all directly connected peers.
    - [x] The `NetworkMessage` content should be a serialized representation of the node's knowledge
      of the network (e.g., its list of direct peers).
    - [x] The `payloadCallback` in `NearbyConnectionsManager` needs to be updated to handle incoming
      `GOSSIP` messages.

## Step 2: Populate the EndpointRegistry

* **Goal:** Use incoming gossip messages to build a picture of the entire network, not just
  immediate peers.
* **Mechanism:** When a `GOSSIP` message is received in `payloadCallback`:

- [x] Parse the message content to get the sender's peer list.
- [x] For each peer in the sender's list, update the `EndpointRegistry`.
- [x] If a peer is new, add it to the registry.
- [x] Update the `distance` and `lastUpdatedTs` for known endpoints. The distance will be
  the hop count from the message + 1.

## Step 3: Implement Intelligent Disconnection

* **Goal:** Use the network knowledge in `EndpointRegistry` to make smart decisions about who to
  disconnect from.
* **Mechanism:**
    - [x]   Uncomment the `disconnectFromEndpoint` function in `NearbyConnectionsManager`.
    - [x]   Create a new function, maybe `findRedundantPeer()`, that analyzes the
      `EndpointRegistry`.
    - This function should identify a peer that is "redundant". A peer is redundant if it is safely
      reachable through other 2-hop paths. The algorithm calculates a "redundancy score" for each
      peer and drops the one with the highest score, ensuring network stability.
    - [x]   Create another function, `findWorstDistantNode()`, that finds a node with a "bad"
      distance (e.g., > 2 or unknown) and that has been heard from in the last 5 minutes. This will
      be the trigger for a reshuffle and avoids trying to connect to offline "zombie" nodes.
    - [x]   Create a recurring job that:
    - [x]   Calls `findWorstDistantNode()` to see if a reshuffle is "worth it".
    - [x]   If a reshuffle is worth it, calls `findRedundantPeer()`.
    - [x]   If a redundant peer is found, disconnect from the redundant peer and connect to the
      worst
      node.

## Step 4: NearbyConnectionsManager.broadcast and NetworkMessage.DISPLAY

*   **Goal:** Implement the "Render Locally" button to work: if checked like it is now continue to
    show the visualization on the same device, but if unchecked, show the visualization on ALL other
    devices.
*   **Mechanism:**
    - [x] The UI has a checkbox "Render Locally".
    - [x] If the checkbox is checked, clicking a visualization navigates the local `WebView` to the
      content's `index.html`.
    - [x] If the checkbox is *unchecked*, the click event calls the
      `JavaScriptInjectedAndroid.sendPeerDisplayCommand` function.
    - [x] This Kotlin function constructs a `NetworkMessage` of type `DISPLAY` with the chosen
      folder as content.
    - [x] It then calls `NearbyConnectionsManager.broadcast` to send this message to all directly
      connected peers.
    - [x] The `payloadCallback` in `NearbyConnectionsManager` handles incoming `DISPLAY` messages.
    - [x] If the message is from another node, it calls `WebAppActivity.navigateTo` to load the
      content in the local `WebView`.
    - [x] After processing, the message is re-broadcast to all other peers to ensure it floods the
      network.
