package info.benjaminhill.localmesh2

/** All Endpoints seen during this app's lifetime: immediate peers, distant connections, and other islands */
data class Endpoint(
    // Unique identifier for this endpoint
    val id: String,
    // Anything: an advertised peer, a gossip, etc
    var lastUpdatedTs: Long,
    // 0=self (not included), 1=direct peer, null=unknown
    var distance: Int?,
    var immediatePeerIds: Set<String>? = null,
    var transferFailureCount: Int = 0,
)