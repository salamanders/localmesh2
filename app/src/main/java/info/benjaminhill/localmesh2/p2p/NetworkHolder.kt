package info.benjaminhill.localmesh2.p2p

/**
 * A singleton object to hold a reference to the `HealingMesh`.
 *
 * This provides a simple way for different parts of the application (like `MainActivity`
 * and `JavaScriptInjectedAndroid`) to access the same `HealingMesh` instance
 * without having to pass it around as a parameter.
 */
object NetworkHolder {
    var connection: HealingMesh? = null

    val localHumanReadableName: String = randomString(5)
}
