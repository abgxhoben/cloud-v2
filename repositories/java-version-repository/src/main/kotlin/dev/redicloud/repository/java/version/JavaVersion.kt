package dev.redicloud.repository.java.version

import dev.redicloud.utils.OSType
import dev.redicloud.utils.getOperatingSystemType
import dev.redicloud.utils.service.ServiceId
import java.io.File
import java.util.*

data class JavaVersion(
    val uniqueId: UUID = UUID.randomUUID(),
    var name: String,
    var id: Int,
    val onlineVersion: Boolean = false,
    val located: MutableMap<ServiceId, String>
) {

    fun isUnknown() = id == -1

    fun isLocated(serviceId: ServiceId) = located.containsKey(serviceId) && File(located[serviceId]!!).exists()

    fun autoLocate(): File? {
        return locateAllJavaVersions().filter {
            when (OSType.WINDOWS) {
                getOperatingSystemType() -> File(it, "bin/java.exe")
                else -> File(it, "bin/java")
            }.exists()
        }.filter { file ->
            val byId = file.name.split("-").any { it.lowercase() == id.toString() }
            val byName = file.name.lowercase() == name.lowercase()
            byId || byName
        }.firstOrNull()
    }

}