# Feature: Stale Peer Purge

**Goal:** To prevent the accumulation of "ghost" peers in the network, ensuring that the total peer
count accurately reflects the current state of the mesh.

## The Problem

The network was suffering from "ghost" peers. When a device disconnected, it was not fully removed
from the collective memory of the network. Its ID would continue to be circulated in gossip
messages.

When a new device joined the network, it would "learn" this entire history of ghost peers from its
neighbors, leading to wildly inflated peer counts (e.g., showing 14 total peers when only 6 devices
were active).

## The Solution

A two-part solution was implemented to create a self-healing mechanism that purges stale peers.

### 1. Reliable "Last Seen" Timestamps

The logic for updating an endpoint's `lastUpdatedTs` was changed. Previously, the timestamp was
updated any time a peer was mentioned in a gossip message. Now, it is **only updated on direct
contact**, such as when a message is received directly from that peer. This prevents the gossip
protocol from keeping ghosts alive indefinitely.

### 2. Periodic Purge Job

A new background job runs on every device every 2 minutes. This job scans the `EndpointRegistry` and
permanently removes any endpoint that has not been heard from directly in over 5 minutes.

This combination ensures that the network actively forgets old peers, maintaining an accurate and
healthy state. To ensure liveness, each node broadcasts a gossip message every 2 minutes to update
its timestamp across the mesh.
