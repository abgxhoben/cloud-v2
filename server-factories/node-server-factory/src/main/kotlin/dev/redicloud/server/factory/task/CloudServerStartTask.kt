package dev.redicloud.server.factory.task

import dev.redicloud.console.utils.toConsoleValue
import dev.redicloud.event.EventManager
import dev.redicloud.logging.LogManager
import dev.redicloud.repository.node.NodeRepository
import dev.redicloud.repository.server.ServerRepository
import dev.redicloud.server.factory.*
import dev.redicloud.server.factory.utils.*
import dev.redicloud.api.events.impl.node.NodeConnectEvent
import dev.redicloud.api.events.impl.node.NodeDisconnectEvent
import dev.redicloud.api.events.impl.node.NodeSuspendedEvent
import dev.redicloud.api.events.listen
import dev.redicloud.api.utils.factory.*
import dev.redicloud.tasks.CloudTask
import dev.redicloud.utils.MultiAsyncAction
import dev.redicloud.utils.coroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext

class CloudServerStartTask(
    private val serverFactory: ServerFactory,
    eventManager: EventManager,
    private val nodeRepository: NodeRepository,
    private val serverRepository: ServerRepository
) : CloudTask() {

    private val onNodeConnect = eventManager.listen<NodeConnectEvent> {
        scope.launch {
            val nodes = nodeRepository.getConnectedNodes()
            val master = nodes.firstOrNull { it.master }
            if (master?.serviceId != serverFactory.hostingId) return@launch
            serverFactory.startQueue.forEach queue@{ info ->
                info.failedStarts.removeFails(it.serviceId)
                info.calculateStartOrder(nodes, serverRepository)
            }
        }
    }

    private val onNodeDisconnect = eventManager.listen<NodeDisconnectEvent> {
        scope.launch {
            val nodes = nodeRepository.getConnectedNodes()
            val master = nodes.firstOrNull { it.master }
            if (master?.serviceId != serverFactory.hostingId) return@launch
            serverFactory.startQueue.forEach queue@{ info ->
                info.failedStarts.addFailedStart(it.serviceId, StartResultType.NODE_NOT_CONNECTED)
                info.calculateStartOrder(nodes, serverRepository)
            }
        }
    }

    private val onNodeSuspend = eventManager.listen<NodeSuspendedEvent> {
        scope.launch {
            val nodes = nodeRepository.getConnectedNodes()
            val master = nodes.firstOrNull { it.master }
            if (master?.serviceId != serverFactory.hostingId) return@launch
            serverFactory.startQueue.forEach queue@{ info ->
                info.failedStarts.addFailedStart(it.serviceId, StartResultType.NODE_IS_NOT_ALLOWED)
                info.calculateStartOrder(nodes, serverRepository)
            }
        }
    }

    companion object {
        private val logger = LogManager.logger(CloudServerStartTask::class)

        @OptIn(DelicateCoroutinesApi::class)
        private val scope = CoroutineScope(newSingleThreadContext("server-factory-start") + coroutineExceptionHandler)
    }

    override suspend fun execute(): Boolean {
        val actions = MultiAsyncAction()
        serverFactory.getStartList().forEach { info ->
            if (!info.isNextNode(serverFactory.hostingId)) return@forEach

            val name = if (info.serviceId == null) info.configurationTemplate.name else info.serviceId?.toName() ?: "unknown"

            actions.add {
                try {
                    serverFactory.startQueue.remove(info)
                    val result = if (info.serviceId == null) {
                        serverFactory.startServer(info.configurationTemplate, serverUniqueId = info.uniqueId)
                    }else {
                        serverFactory.startServer(info.serviceId, null)
                    }

                    when (result.type) {

                        StartResultType.ALREADY_RUNNING -> {
                            serverFactory.startQueue.remove(info)
                            info.addFailedStart(serverFactory.hostingId, StartResultType.ALREADY_RUNNING)
                            info.addFailedNode(serverFactory.hostingId)
                            serverFactory.startQueue.remove(info)
                            result as AlreadyRunningStartResult
                            logger.severe("§cServer ${result.server.identifyName(false)} was removed from the start queue because it is already running!")
                        }

                        StartResultType.RAM_USAGE_TOO_HIGH -> {
                            serverFactory.startQueue.remove(info)
                            info.addFailedStart(serverFactory.hostingId, StartResultType.RAM_USAGE_TOO_HIGH)
                            info.addFailedNode(serverFactory.hostingId)
                            serverFactory.startQueue.add(info)
                            logger.warning("§cCan´t start server ${toConsoleValue(name, false)} because the ram usage is too high!")
                        }

                        StartResultType.TOO_MUCH_SERVICES_OF_TEMPLATE -> {
                            serverFactory.startQueue.remove(info)
                            info.addFailedStart(serverFactory.hostingId, StartResultType.TOO_MUCH_SERVICES_OF_TEMPLATE)
                            if (result is TooMuchServicesOfTemplateOnNodeStartResult) {
                                info.addFailedNode(serverFactory.hostingId)
                                serverFactory.startQueue.add(info)
                                logger.warning("§cCan´t start server ${toConsoleValue(name, false)} on this node because there are too much services of this template!")
                            }

                        }

                        StartResultType.UNKNOWN_SERVER_VERSION -> {
                            serverFactory.startQueue.remove(info)
                            info.addFailedStart(serverFactory.hostingId, StartResultType.UNKNOWN_SERVER_VERSION)
                            info.addFailedNode(serverFactory.hostingId)
                            logger.warning("§cCan´t start server ${toConsoleValue(name, false)} because the server version is not set!")
                        }

                        StartResultType.NODE_IS_NOT_ALLOWED -> {
                            serverFactory.startQueue.remove(info)
                            info.addFailedStart(serverFactory.hostingId, StartResultType.NODE_IS_NOT_ALLOWED)
                            info.addFailedNode(serverFactory.hostingId)
                            serverFactory.startQueue.add(info)
                        }

                        StartResultType.NODE_NOT_CONNECTED -> {
                            info.addFailedStart(serverFactory.hostingId, StartResultType.NODE_NOT_CONNECTED)
                            info.addFailedNode(serverFactory.hostingId)
                            serverFactory.startQueue.remove(info)
                            serverFactory.startQueue.add(info)
                        }

                        StartResultType.UNKNOWN_JAVA_VERSION -> {
                            info.addFailedStart(serverFactory.hostingId, StartResultType.UNKNOWN_JAVA_VERSION)
                            info.addFailedNode(serverFactory.hostingId)
                            serverFactory.startQueue.remove(info)
                            logger.severe("§cCan´t start server ${toConsoleValue(name, false)} because the java version is not set!")
                        }

                        StartResultType.JAVA_VERSION_NOT_INSTALLED -> {
                            info.addFailedStart(serverFactory.hostingId, StartResultType.JAVA_VERSION_NOT_INSTALLED)
                            info.addFailedNode(serverFactory.hostingId)
                            serverFactory.startQueue.remove(info)
                            result as JavaVersionNotInstalledStartResult
                            logger.severe("§cCan´t start server ${toConsoleValue(name, false)} because the java version '${result.javaVersion.name} is not installed!")
                        }

                        StartResultType.UNKNOWN_SERVER_TYPE_VERSION -> {
                            info.addFailedStart(serverFactory.hostingId, StartResultType.UNKNOWN_SERVER_TYPE_VERSION)
                            info.addFailedNode(serverFactory.hostingId)
                            serverFactory.startQueue.remove(info)
                            logger.severe("§cCan´t start server ${toConsoleValue(name, false)} because the server type version is not set!")
                        }

                        StartResultType.UNKNOWN_CONFIGURATION_TEMPLATE -> {
                            info.addFailedStart(serverFactory.hostingId, StartResultType.UNKNOWN_CONFIGURATION_TEMPLATE)
                            serverFactory.startQueue.remove(info)
                            logger.severe("§cCan´t start static server ${toConsoleValue(name, false)} because the configuration template is unknown?!")
                        }

                        StartResultType.UNKNOWN_ERROR -> {
                            info.addFailedStart(serverFactory.hostingId, StartResultType.UNKNOWN_ERROR)
                            info.addFailedNode(serverFactory.hostingId)
                            serverFactory.startQueue.remove(info)
                            serverFactory.startQueue.add(info)
                            val errorResult = result as UnknownErrorStartResult
                            logger.severe(
                                "§cAn unknown error occurred while starting server ${toConsoleValue(name, false)}!",
                                errorResult.throwable
                            )
                        }

                        else -> {}
                    }
                }catch (e: Exception) {
                    logger.severe("§cAn error occurred while starting server ${toConsoleValue(name, false)}!", e)
                }
            }
        }
        actions.joinAll()
        return false
    }

}