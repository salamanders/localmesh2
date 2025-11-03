package info.benjaminhill.localmesh2.p2p

/**
 * A singleton object to hold a reference to the `HealingMeshConnection`.
 *
 * This provides a simple way for different parts of the application (like `MainActivity`
 * and `JavaScriptInjectedAndroid`) to access the same `HealingMeshConnection` instance
 * without having to pass it around as a parameter.
 */
object NetworkHolder {
    var connection: HealingMeshConnection? = null
}
