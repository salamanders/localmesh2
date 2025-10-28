package info.benjaminhill.localmesh2

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object RoleManager {
    private val _role = MutableStateFlow(Role.LIEUTENANT)
    val role = _role.asStateFlow()

    fun setRole(newRole: Role) {
        _role.value = newRole
    }
}
