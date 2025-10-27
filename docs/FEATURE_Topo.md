# Refactor NearbyConnectionsManager

## Goal

Refactor the `NearbyConnectionsManager` object to improve separation of concerns and reduce its
complexity. This will make the code easier to understand, maintain, and debug.

## Problem

The current `NearbyConnectionsManager` is a large "god object" that handles both low-level Nearby
Connections API interactions and high-level network topology management. This makes the code
difficult to reason about and maintain.

## Proposed Solution

Split `NearbyConnectionsManager` into two distinct objects:

1. **`NearbyConnectionsManager`**: This object will be responsible for all the low-level
   interactions with the Google Nearby Connections API. Its responsibilities will include:
    * Advertising and discovering endpoints.
    * Managing the lifecycle of connections (requesting, accepting, disconnecting).
    * Sending and receiving payloads.
    * It will act as the "hands" of the network, executing commands from the `TopologyOptimizer`.

2. **`TopologyOptimizer`**: This object will be the "brains" of the network. It will contain all the
   high-level logic for analyzing network health and making decisions to optimize the network
   topology. Its responsibilities will include:
    * Deciding when to connect to new endpoints.
    * Implementing the gossiping protocol to share network state.
    * Running the reshuffling logic to improve the network topology by finding and replacing
      redundant or inefficient connections.

## Relationship

The two objects will collaborate using composition:

* `TopologyOptimizer` will hold a reference to `NearbyConnectionsManager` to execute its decisions (
  e.g., `requestConnection`, `sendPayload`).
* `NearbyConnectionsManager` will notify `TopologyOptimizer` of network events (e.g.,
  `onEndpointFound`, `onPayloadReceived`) so that it can make informed decisions.

## Constraints & Urgency

* **Pure Refactor:** This is a pure refactoring task. No new features or logic should be introduced.
  The goal is to move existing code into new, more appropriate homes. We must be careful to avoid
  the previous issue where the `TopologyOptimizer` was making too many changes.
* **No Added Complexity:** The refactoring should simplify the overall design, not add complexity
  for "future-proofing".
* **24-Hour Deadline:** This work is under a tight 24-hour deadline. The refactoring is intended to
  make the code more robust and easier to debug, which will help in meeting this deadline.

We know we did well when:

1. The imports are cleanly split. Ideally there should be very few "
   com.google.android.gms.nearby.connection" outside of NearbyConnectionsManager.
2. There is no major expension of code beyond some very simple glue.
3. There is no chance of race conditions between the two classes.
4. The docs/FLOW.md is fully updated with these changes.
5. It compiles cleanly.