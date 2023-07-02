package dev.redicloud.service.base.suggester

import dev.redicloud.commands.api.CommandContext
import dev.redicloud.commands.api.ICommandSuggester
import dev.redicloud.repository.server.version.CloudServerVersionType
import dev.redicloud.repository.server.version.CloudServerVersionTypeRepository
import dev.redicloud.utils.SingleCache
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds

class CloudServerVersionTypeSuggester(
    private val serverVersionTypeRepository: CloudServerVersionTypeRepository
) : ICommandSuggester {

    private val easyCache = SingleCache(5.seconds) { serverVersionTypeRepository.getTypes().map { it.name }.toTypedArray() }

    override fun suggest(context: CommandContext): Array<String> = easyCache.get()!!

}