# Refactor: Separate Topology Optimizer from Connection Management

## Goals

- To refactor `NearbyConnectionsManager` by separating the low-level connection management from the high-level topology optimization logic.
- To improve code clarity, flexibility, and testability.
- To follow the "Composition over Inheritance" design principle.

## Design

We will split the existing `NearbyConnectionsManager` into two distinct classes:

1.  **`NearbyConnectionsManager` (Refactored):**
    - This class will be responsible *only* for the direct interactions with the `com.google.android.gms.nearby.connection` API.
    - It will manage the connection lifecycle, advertising, discovery, and payload transfers.
    - It will hold a reference to a `TopologyOptimizer` and will consult it when making decisions.
    - It will not contain any logic specific to the "snake" topology (e.g., reshuffling, connection count rules).

2.  **`TopologyOptimizer` (New Class):**
    - This class will contain all the high-level logic for maintaining the desired mesh topology.
    - It will be responsible for the "reshuffling" logic.
    - It will provide decision-making methods like `shouldConnectTo(endpointId, theirConnectionCount)` and `selectEndpointToDisconnect()`.
    - **Crucially, this class will have no `import` statements from `com.google.android.gms.nearby.connection`.** It will work with pure data types (Strings, Ints, Maps).

## List of Changes

- [x] Create the new file `app/src/main/java/info/benjaminhill/localmesh2/TopologyOptimizer.kt`.
- [x] Define the `TopologyOptimizer` class structure with placeholder methods.
- [x] Move the reshuffling logic (`startReshuffling`, `reshuffleJob`) from `NearbyConnectionsManager` to `TopologyOptimizer`.
- [x] Move the topology-related decision logic from `endpointDiscoveryCallback` in `NearbyConnectionsManager` into `TopologyOptimizer`.
- [x] Move the topology-related decision logic from `onDisconnected` in `NearbyConnectionsManager` into `TopologyOptimizer`.
- [x] In `NearbyConnectionsManager`, create an instance of `TopologyOptimizer`.
- [x] Update `NearbyConnectionsManager` to call the new methods on `TopologyOptimizer` to make decisions.
- [x] Remove all `com.google.android.gms.nearby.connection` imports and dependencies from `TopologyOptimizer`.
- [x] Verify the refactoring by building the project.
- [ ] Delete `REFACTOR_TOPOLOGY_OPTIMIZER.md`.
