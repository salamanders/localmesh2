# Enhanced Peer Dropping Algorithm

**Status: Implemented**
This feature has been implemented as described. The document is kept for historical and architectural reference.

---

## 1. Goal

To improve network resilience when a new node joins a full mesh. This document proposes a new
algorithm for the `findRedundantPeer()` function, which is invoked when a node at its connection
limit needs to disconnect from an existing peer to make room for a new one.

## 2. Problem with the Current Approach

The current implementation of `findRedundantPeer()` selects the peer with the highest number of its
own connections (`immediateConnections`).

```kotlin
// Current Logic
return connectedPeers.maxByOrNull { it.immediateConnections ?: 0 }
```

This approach uses the peer's connection count as a heuristic for "importance" or "redundancy".
However, it has a significant weakness: it does not guarantee that the dropped peer will remain
reachable through a 2-hop path. This could lead to network fragmentation if a poor choice is made.

## 3. Proposed New Algorithm: Redundancy Score

The new algorithm will select a peer to drop based on its "redundancy score". This score is defined
as **the number of other immediate peers that provide a 2-hop path back to the peer in question.**

A peer that is reachable through multiple other direct connections is truly redundant, making it the
safest to disconnect from directly.

### 3.1. Required Data Model Changes

To calculate the redundancy score, a node needs to know the direct connections of its own peers.
This requires a change to the data model.

**1. `Endpoint` Data Class:** The `immediateConnections` count will be replaced with a set of peer
IDs.

```kotlin
// from
data class Endpoint(
    var immediateConnections: Int?,
)

// to
data class Endpoint(
    var immediatePeerIds: Set<String>? = null
) {
    val immediateConnections: Int?
        get() = immediatePeerIds?.size
}
```

**2. Gossip Processing:** The `onPayloadReceived` handler for `GOSSIP` messages must be updated to
store the gossiped list of peers in the sender's `Endpoint` object.

```kotlin
// in onPayloadReceived...
val gossip = Json.decodeFromString(Gossip.serializer(), receivedMessage.messageContent!!)
val senderEndpoint = EndpointRegistry.get(endpointId)

// NEW: Store the full set of the sender's peers
senderEndpoint.immediatePeerIds = gossip.peers 
```

### 3.2. New `findRedundantPeer()` Algorithm

With the necessary data available, the new algorithm will be as follows:

1. For each `peerToDrop` among the current node's immediate connections, calculate its
   `redundancyScore`.
2. The `redundancyScore` is the count of *other* immediate peers who list `peerToDrop` in their own
   `immediatePeerIds` set.
3. Select the peer with the **highest `redundancyScore`**.
4. **Tie-Breaking:** If multiple peers share the same highest score, select the one among them that
   has the highest `immediateConnections` count (the old heuristic).

### 3.3. Example

- **`Me`** is at its connection limit, with immediate peers: `{P1, P2, P3, P4}`.
- `Me` has received gossip and knows the connections of its peers:
    - `P2.immediatePeerIds` = `{Me, P1, P5}`
    - `P3.immediatePeerIds` = `{Me, P1, P6}`
    - `P4.immediatePeerIds` = `{Me, P7}`
- `Me` calculates the `redundancyScore` for each of its peers:
    - **Score for `P1`:**
        - Is `P1` in `P2`'s peer list? **Yes**.
        - Is `P1` in `P3`'s peer list? **Yes**.
        - Is `P1` in `P4`'s peer list? No.
        - **`P1`'s score is 2.**
    - **Score for `P2`:**
        - (Assuming `P1`, `P3`, `P4` do not list `P2` as a peer)
        - **`P2`'s score is 0.**
    - ... and so on for `P3` and `P4`.

**Conclusion:** `P1` has the highest redundancy score (2). It is the safest peer to drop because
`Me` can still reach `P1` through two different 2-hop paths (`Me->P2->P1` and `Me->P3->P1`).

## 4. Benefits

- **Increased Resilience:** Explicitly ensures that a dropped peer remains part of the mesh through
  a 2-hop connection, preventing network fragmentation.
- **Smarter Topology Management:** Makes more intelligent decisions when integrating new nodes,
  leading to a healthier and more stable network topology over time.
