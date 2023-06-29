package dev.redicloud.service.base.parser

import dev.redicloud.commands.api.CommandArgumentParser
import dev.redicloud.repository.server.version.CloudServerVersion
import dev.redicloud.repository.server.version.ServerVersionRepository
import kotlinx.coroutines.runBlocking
import java.util.*

class CloudServerVersionParser(private val serverVersionRepository: ServerVersionRepository) :
    CommandArgumentParser<CloudServerVersion> {

    override fun parse(parameter: String): CloudServerVersion? {
        return runBlocking {
            try {
                val uniqueId = UUID.fromString(parameter)
                serverVersionRepository.getVersion(uniqueId)
            } catch (e: IllegalArgumentException) {
                serverVersionRepository.getVersion(parameter)
            }
        }
    }
}