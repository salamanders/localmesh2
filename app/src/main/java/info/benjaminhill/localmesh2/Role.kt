package info.benjaminhill.localmesh2

enum class Role(val advertisedServiceId: String?) {
    HUB("LM_HUB"),
    LIEUTENANT("LM_LIEUTENANT"),
    CLIENT(null)
}
