package info.benjaminhill.localmesh2

enum class Role(val advertisedServiceId: String?) {
    COMMANDER("LM_COMMANDER"),
    LIEUTENANT("LM_LIEUTENANT"),
    CLIENT(null)
}
