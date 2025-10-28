# Feature: Most Simple Topology

## Goal

Greatly simplify the network connection logic to improve stability and robustness, even at the cost
of a perfectly optimized topology. The current `TopologyOptimizer` is too complex and appears to be
causing connectivity problems.

## Design

The core idea is to maintain a simple, target number of connections (3-6) using a straightforward,
periodic process. This moves away from complex, event-driven topology optimization in favor of a
more robust, state-driven approach.

### Core Logic

1. **Target Connections:** Always try to stay connected to between 3 and 6 peers.
2. **Proactive Connections:** Every 30 seconds, check the number of connected peers. If the count is
   less than 3, discover and connect to random available peers until the minimum is met.
3. **Connection State Proof:** The definitive source of truth for a "connection" will be a
   successful `onPayloadTransferUpdate` after a `sendToAll` broadcast. A successful data transfer is
   a much stronger guarantee of a healthy link than `onConnectionResult`.
4. **Peer Discovery:** Do not expire discovered peers from the list of potential candidates. It
   seems that endpoint advertisements are not reliably refreshed, so we will keep all discovered
   endpoints as potential connection candidates indefinitely.
5. **Connection Slot Management:**
    * Leave 3 connection slots open for inbound connections to allow other peers to connect to us.
    * If an inbound connection request is received and we already have 6 or more connections, reject
      the new connection to avoid exceeding the hardware limit (around 7).

## Implementation Checklist

- [ ] Modify `TopologyOptimizer` to implement the new 30-second connection check.
- [ ] Remove the existing complex logic for finding redundant peers and making room.
- [ ] Change the connection acceptance logic in `NearbyConnectionsManager` to reject if connection
  count is >= 6.
- [ ] Update the logic to rely on `onPayloadTransferUpdate` as the primary signal of a successful
  connection, potentially removing reliance on `onConnectionResult` for marking a peer as
  `distance=1`.
- [ ] Trigger this `onPayloadTransferUpdate` by immediately gossiping following a successful
  connection.
- [ ] The "3 in a row" failed connection counter becomes "3 failed payloads in a row". The only
  impact of 3 failed payloads is to remove them from the "imemediate peer" list, they should be kept
  in the full list so they will be candidates for future random connections.
- [ ] Ensure discovered endpoints are not purged from the `availablePeers` list.
