# Plan to Get Mesh Networking Working Quickly

This document outlines the shortest path to a functional mesh network.

## Step 1: Implement Gossip Messaging

* **Goal:** Periodically broadcast the node's status to its immediate peers.
* **Mechanism:**
    - [ ] In `NearbyConnectionsManager`, create a recurring job that sends a `GOSSIP`
      `NetworkMessage` to all directly connected peers.
    - [ ] The `NetworkMessage` content should be a serialized representation of the node's knowledge
      of the network (e.g., its list of direct peers).
    - [ ] The `payloadCallback` in `NearbyConnectionsManager` needs to be updated to handle incoming
      `GOSSIP` messages.

## Step 2: Populate the EndpointRegistry

* **Goal:** Use incoming gossip messages to build a picture of the entire network, not just
  immediate peers.
* **Mechanism:** When a `GOSSIP` message is received in `payloadCallback`:

- [ ] Parse the message content to get the sender's peer list.
- [ ] For each peer in the sender's list, update the `EndpointRegistry`.
- [ ] If a peer is new, add it to the registry.
- [ ] Update the `distance` and `lastUpdatedTs` for known endpoints. The distance will be
  the hop count from the message + 1.

## Step 3: Implement Intelligent Disconnection

* **Goal:** Use the network knowledge in `EndpointRegistry` to make smart decisions about who to
  disconnect from.
* **Mechanism:**
    - [ ]   Uncomment the `disconnectFromEndpoint` function in `NearbyConnectionsManager`.
    - [ ]   Create a new function, maybe `findRedundantPeer()`, that analyzes the
      `EndpointRegistry`.
    - [ ] This function should identify a peer that is "redundant". A peer is redundant if: (You
      have at least 2 other peers. Your other peers are also connected to the redundant peer.)
    - [ ]   Create another function, `findWorstDistantNode()`, that finds the node with the highest
      `distance` (or `null` distance) in the `EndpointRegistry`.
    - [ ]   Create a recurring job that:
    - [ ]   Calls `findRedundantPeer()`.
    - [ ]   If a redundant peer is found, calls `findWorstDistantNode()`.
    - [ ]  If a "worst" node is found, disconnect from the redundant peer and connect to the worst
      node.
