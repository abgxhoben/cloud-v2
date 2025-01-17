package dev.redicloud.server.factory

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Session
import dev.redicloud.api.events.impl.server.CloudServerDeleteEvent
import dev.redicloud.api.events.impl.server.CloudServerDisconnectedEvent
import dev.redicloud.api.events.impl.server.CloudServerTransferredEvent
import dev.redicloud.api.server.factory.ICloudServerFactory
import dev.redicloud.api.service.ServiceId
import dev.redicloud.api.service.ServiceType
import dev.redicloud.api.service.server.CloudServerState
import dev.redicloud.api.template.configuration.ICloudConfigurationTemplate
import dev.redicloud.api.utils.STATIC_FOLDER
import dev.redicloud.api.utils.TEMP_FILE_TRANSFER_FOLDER
import dev.redicloud.api.utils.factory.ServerQueueInformation
import dev.redicloud.api.utils.toUniversalPath
import dev.redicloud.cluster.file.FileCluster
import dev.redicloud.console.Console
import dev.redicloud.database.DatabaseConnection
import dev.redicloud.event.EventManager
import dev.redicloud.logging.LogManager
import dev.redicloud.packets.PacketManager
import dev.redicloud.repository.java.version.JavaVersionRepository
import dev.redicloud.repository.node.CloudNode
import dev.redicloud.repository.node.NodeRepository
import dev.redicloud.repository.server.CloudMinecraftServer
import dev.redicloud.repository.server.CloudProxyServer
import dev.redicloud.repository.server.CloudServer
import dev.redicloud.repository.server.ServerRepository
import dev.redicloud.repository.server.version.CloudServerVersionRepository
import dev.redicloud.repository.server.version.CloudServerVersionTypeRepository
import dev.redicloud.repository.service.ServiceSessions
import dev.redicloud.repository.template.configuration.ConfigurationTemplate
import dev.redicloud.repository.template.configuration.ConfigurationTemplateRepository
import dev.redicloud.repository.template.file.AbstractFileTemplateRepository
import dev.redicloud.server.factory.screens.ServerScreen
import dev.redicloud.server.factory.screens.ServerScreenParser
import dev.redicloud.server.factory.screens.ServerScreenSuggester
import dev.redicloud.server.factory.utils.*
import dev.redicloud.service.base.utils.ClusterConfiguration
import dev.redicloud.utils.MultiAsyncAction
import dev.redicloud.utils.defaultScope
import dev.redicloud.utils.ioScope
import dev.redicloud.utils.zipFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.*

class ServerFactory(
    databaseConnection: DatabaseConnection,
    private val nodeRepository: NodeRepository,
    private val serverRepository: ServerRepository,
    private val serverVersionRepository: CloudServerVersionRepository,
    private val serverVersionTypeRepository: CloudServerVersionTypeRepository,
    private val fileTemplateRepository: AbstractFileTemplateRepository,
    private val javaVersionRepository: JavaVersionRepository,
    private val packetManager: PacketManager,
    private val bindHost: String,
    private val console: Console,
    private val clusterConfiguration: ClusterConfiguration,
    private val configurationTemplateRepository: ConfigurationTemplateRepository,
    private val eventManager: EventManager,
    private val fileCluster: FileCluster
) : ICloudServerFactory, RemoteServerFactory(databaseConnection, nodeRepository, serverRepository) {

    companion object {
        private val logger = LogManager.logger(ServerFactory::class)
    }

    override val hostedProcesses: MutableList<ServerProcess> = mutableListOf()
    private val idLock = databaseConnection.getLock("server-factory:id-lock")

    init {
        console.commandManager.registerParser(ServerScreen::class.java, ServerScreenParser(console))
        console.commandManager.registerSuggesters(ServerScreenSuggester(console))
    }

    suspend fun getStartList(): List<ServerQueueInformation> {
        return startQueue.toMutableList().sortedWith(compareByDescending<ServerQueueInformation>
        {
            if (it.serviceId != null) {
                val configuration = runBlocking {
                    serverRepository.getServer<CloudServer>(it.serviceId!!)?.configurationTemplate
                }
                configuration?.startPriority ?: 50
            } else {
                it.configurationTemplate.startPriority
            }
        }.thenByDescending { it.queueTime }).toList()
    }

    internal suspend fun deleteServer(serviceId: ServiceId): Boolean {
        if (!serviceId.type.isServer()) {
            throw IllegalArgumentException("Service id that was queued for deletion is not a server: ${serviceId.toName()}")
        }
        val server = serverRepository.getServer<CloudServer>(serviceId) ?: return false
        if (server.hostNodeId != hostingId) {
            return false
        }
        if (server.state != CloudServerState.STOPPED) {
            return false
        }
        if (!server.configurationTemplate.static) {
            throw IllegalArgumentException("Service id that was queued for deletion is not static: ${serviceId.toName()}")
        }
        this.unregisterServer(serviceId, server)
        val workDir = File(STATIC_FOLDER.getFile(), "${server.name}-${server.serviceId.id}")
        if (workDir.exists() && workDir.isDirectory) {
            if (!workDir.deleteRecursively()) {
                workDir.deleteOnExit()
            }
        }
        eventManager.fireEvent(CloudServerDeleteEvent(server.serviceId, server.name))
        return true
    }

    /**
     * Starts a server with the given configuration template and returns the result
     * @param configurationTemplate the configuration template to use
     * @param force if the server should be started even if the configuration template does not allow it (e.g. max memory)
     * @return the result of the start
     */
    internal suspend fun startServer(
        configurationTemplate: ICloudConfigurationTemplate,
        force: Boolean = false,
        serverUniqueId: UUID? = null
    ): StartResult {
        logger.fine("Prepare server ${configurationTemplate.uniqueId}...")

        val serverDataLoadResult = loadServerData(configurationTemplate)
        if (serverDataLoadResult.second != null) return serverDataLoadResult.second!!
        val snapshotData: StartDataSnapshot = serverDataLoadResult.first

        val serviceId = ServiceId(
            serverUniqueId ?: UUID.randomUUID(),
            if (snapshotData.versionType.proxy) ServiceType.PROXY_SERVER else ServiceType.MINECRAFT_SERVER
        )

        // create the server process
        val serverProcess = ServerProcess(
            configurationTemplate,
            serverRepository,
            packetManager,
            eventManager,
            bindHost,
            clusterConfiguration,
            serviceId,
            hostingId
        )

        hostedProcesses.add(serverProcess)
        val cloudServer: CloudServer?
        try {

            val thisNode = nodeRepository.getNode(hostingId)!!
            if (!force) {
                canStartOnNode(thisNode, configurationTemplate).let {
                    if (it != null) {
                        hostedProcesses.remove(serverProcess)
                        return it
                    }
                }
            }
            idLock.lock()
            try {
                // get the next id for the server and create it
                cloudServer = if (snapshotData.versionType.proxy) {
                    serverRepository.createServer(
                        CloudProxyServer(
                            serviceId,
                            configurationTemplate,
                            getIdForServer(configurationTemplate),
                            thisNode.serviceId,
                            ServiceSessions(),
                            false,
                            CloudServerState.PREPARING,
                            -1,
                            configurationTemplate.maxPlayers
                        )
                    )
                } else {
                    serverRepository.createServer(
                        CloudMinecraftServer(
                            serviceId,
                            configurationTemplate,
                            getIdForServer(configurationTemplate),
                            thisNode.serviceId,
                            ServiceSessions(),
                            false,
                            CloudServerState.PREPARING,
                            -1,
                            configurationTemplate.maxPlayers
                        )
                    )
                }
            } finally {
                Thread.sleep(50)
                idLock.unlock()
            }
            serverProcess.cloudServer = cloudServer!!

            // Create server screen
            val serverScreen = ServerScreen(cloudServer.serviceId, cloudServer.name, this.console, this.packetManager)
            console.createScreen(serverScreen)

            if (!snapshotData.versionHandler.isPatched(snapshotData.version)
                && snapshotData.versionHandler.isPatchVersion(snapshotData.version)
            ) {
                snapshotData.versionHandler.patch(snapshotData.version)
            }

            // Add service to node database object
            thisNode.hostedServers.add(cloudServer.serviceId)
            nodeRepository.updateNode(thisNode)

            // copy the files to copy server necessary files
            val copier = FileCopier(
                serverProcess,
                cloudServer,
                serverVersionTypeRepository,
                fileTemplateRepository,
                snapshotData
            )
            serverProcess.fileCopier = copier

            // copy all templates
            copier.copyTemplates()
            // copy all version files
            copier.copyVersionFiles { serverProcess.replacePlaceholders(it, snapshotData) }
            // delete old connector files
            copier.deleteConnectors()
            // copy connector
            copier.copyConnector()

            // start the server
            return serverProcess.start(cloudServer, serverScreen, snapshotData)
        } catch (e: Exception) {
            // Make sure to remove the server process from the hosted processes so no memory will be blocked
            hostedProcesses.remove(serverProcess)
            // delete the server if it is created and not static
            try {
                stopServer(serviceId, internalCall = true)
            } catch (_: NullPointerException) {
            }
            return UnknownErrorStartResult(e)
        }
    }

    internal suspend fun unregisterServer(
        serviceId: ServiceId,
        cachedServer: CloudServer? = null,
        force: Boolean = false
    ) {
        if (!serverRepository.databaseConnection.connected) return
        val server = cachedServer ?: serverRepository.getServer(serviceId)
        ?: throw NullPointerException("Server ${serviceId.toName()} not found")
        if (!force && server.state != CloudServerState.STOPPED) {
            throw IllegalArgumentException("Server ${serviceId.toName()} is not stopped")
        }
        serverRepository.deleteServer(server)
    }


    internal suspend fun startServer(
        serviceId: ServiceId?,
        configurationTemplate: ConfigurationTemplate?,
        force: Boolean = false
    ): StartResult {
        if (serviceId == null && configurationTemplate == null) {
            throw NullPointerException("serviceId and configurationTemplate are null")
        }
        if (serviceId == null) {
            return startServer(configurationTemplate!!, force)
        }
        if (!serviceId.type.isServer()) {
            throw IllegalArgumentException("Queued service id to start a server must be a server!")
        }
        logger.fine("Prepare static server ${serviceId.toName()}...")
        val server = serverRepository.getServer<CloudServer>(serviceId)
            ?: throw NullPointerException("Static server ${serviceId.toName()} not found")
        val newConfigurationTemplate =
            configurationTemplateRepository.getTemplate(server.configurationTemplate.uniqueId)
                ?: return UnknownConfigurationTemplateStartResult(server.configurationTemplate.uniqueId)

        val serverDataLoadResult = loadServerData(newConfigurationTemplate)
        if (serverDataLoadResult.second != null) return serverDataLoadResult.second!!
        val snapshotData: StartDataSnapshot = serverDataLoadResult.first

        // create the server process
        val serverProcess = ServerProcess(
            newConfigurationTemplate,
            serverRepository,
            packetManager,
            eventManager,
            bindHost,
            clusterConfiguration,
            serviceId,
            hostingId
        )
        hostedProcesses.add(serverProcess)
        try {
            val thisNode = nodeRepository.getNode(hostingId)!!
            if (!force) {
                canStartOnNode(thisNode, newConfigurationTemplate).let {
                    if (it != null) {
                        hostedProcesses.remove(serverProcess)
                        return it
                    }
                }
            }

            server.configurationTemplate = newConfigurationTemplate
            server.hostNodeId = thisNode.serviceId
            server.state = CloudServerState.PREPARING
            server.maxPlayers = newConfigurationTemplate.maxPlayers
            server.port = -1
            serverRepository.updateServer(server)

            if (!snapshotData.versionHandler.isPatched(snapshotData.version)
                && snapshotData.versionHandler.isPatchVersion(snapshotData.version)
            ) {
                snapshotData.versionHandler.patch(snapshotData.version)
            }

            // Create server screen
            val serverScreen = ServerScreen(server.serviceId, server.name, this.console, this.packetManager)
            console.createScreen(serverScreen)

            // Add service to node database object
            thisNode.hostedServers.add(server.serviceId)
            nodeRepository.updateNode(thisNode)

            // copy the files to copy server necessary files
            val copier = FileCopier(
                serverProcess,
                server,
                serverVersionTypeRepository,
                fileTemplateRepository,
                snapshotData
            )
            serverProcess.fileCopier = copier

            // copy all templates
            copier.copyTemplates(false)
            // copy all version files
            copier.copyVersionFiles(false) { serverProcess.replacePlaceholders(it, snapshotData) }
            // delete old connector files
            copier.deleteConnectors()
            // copy connector
            copier.copyConnector()

            // start the server
            return serverProcess.start(server, serverScreen, snapshotData)
        } catch (e: Exception) {
            // Make sure to remove the server process from the hosted processes so no memory will be blocked
            hostedProcesses.remove(serverProcess)
            // delete the server if it is created and not static
            try {
                stopServer(serviceId, force = true, internalCall = true)
            } catch (_: NullPointerException) {
            }
            return UnknownErrorStartResult(e)
        }
    }

    internal suspend fun stopServer(
        serviceId: ServiceId,
        force: Boolean = true,
        internalCall: Boolean = false
    ) {
        if (!serverRepository.databaseConnection.connected) {
            return
        }
        val server = serverRepository.getServer<CloudServer>(serviceId)
            ?: throw NullPointerException("Server not found")
        if (server.hostNodeId != hostingId) {
            throw IllegalArgumentException("Server is not on this node")
        }
        if (server.state == CloudServerState.STOPPED && !force || server.state == CloudServerState.STOPPING && !force) {
            return
        }

        val thisNode = nodeRepository.getNode(hostingId)
        if (thisNode != null) {
            thisNode.currentMemoryUsage = hostedProcesses.toList()
                .filter { it.serverId != serviceId }
                .sumOf { it.configurationTemplate.maxMemory }
            if (thisNode.currentMemoryUsage < 0) thisNode.currentMemoryUsage = 0
            thisNode.hostedServers.remove(hostingId)
            nodeRepository.updateNode(thisNode)
        }

        val process = hostedProcesses.firstOrNull { it.serverId == serviceId }
        if (process != null) {
            process.stop(force, internalCall)
            hostedProcesses.remove(process)
        }

        if (!internalCall) {
            server.state = CloudServerState.STOPPED
            server.port = -1
            server.connected = false
            server.connectedPlayers.clear()
            serverRepository.updateServer(server)
            eventManager.fireEvent(CloudServerDisconnectedEvent(server.serviceId))

            if (server.unregisterAfterDisconnect()) {
                this.unregisterServer(server.serviceId, server)
            }
        }
    }

    internal suspend fun transferServer(
        serverId: ServiceId,
        nodeId: ServiceId
    ): Boolean {
        if (!serverRepository.databaseConnection.connected) return false
        val server = serverRepository.getServer<CloudServer>(serverId) ?: return false
        if (!server.configurationTemplate.static) return false
        if (server.state != CloudServerState.STOPPED) return false
        if (server.hostNodeId != hostingId) return false
        if (server.hostNodeId == nodeId) return false
        var session: Session? = null
        var channel: ChannelSftp? = null
        try {
            session = fileCluster.createSession(nodeId)
            channel = fileCluster.openChannel(session)
            val serverDir = File(STATIC_FOLDER.getFile(), "${server.name}-${serverId.id}")
            if (!serverDir.exists()) return false
            val id = UUID.randomUUID()
            val workFolder = File(TEMP_FILE_TRANSFER_FOLDER.getFile().absolutePath, id.toString())
            workFolder.mkdirs()
            fileCluster.mkdirs(channel, toUniversalPath(workFolder))
            fileCluster.mkdirs(channel, toUniversalPath(STATIC_FOLDER.getFile()))
            val zip = File(workFolder, "data.zip")
            zipFile(serverDir.absolutePath, zip.absolutePath)
            fileCluster.shareFile(channel, zip, toUniversalPath(workFolder), "data.zip")
            val response = fileCluster.unzip(nodeId, toUniversalPath(zip), toUniversalPath(STATIC_FOLDER.getFile()))
            if (response == null) {
                logger.warning("§cUnzip process does not response of transferring server ${serverId.toName()} to node ${nodeId.toName()}")
            }
            fileCluster.deleteFolderRecursive(channel, toUniversalPath(workFolder))
            workFolder.deleteRecursively()
            val event = CloudServerTransferredEvent(serverId, server.hostNodeId)
            server.hostNodeId = nodeId
            serverRepository.updateServer(server)
            eventManager.fireEvent(event)
            return true
        } catch (e: Exception) {
            logger.severe("§cError while transferring server ${serverId.toName()} to node ${nodeId.toName()}", e)
        } finally {
            channel?.disconnect()
            session?.disconnect()
        }
        return false
    }

    /**
     * Shuts down the server factory and all running processes
     */
    suspend fun shutdown(force: Boolean = false) {
        if (!force && shutdown) return
        shutdown = true
        val actions = MultiAsyncAction()
        hostedProcesses.toList().forEach {
            actions.add {
                try {
                    stopServer(it.cloudServer!!.serviceId, force)
                } catch (e: Exception) {
                    try {
                        stopServer(it.cloudServer!!.serviceId, true)
                    } catch (e1: Exception) {
                        if (e1 is NullPointerException) return@add
                        logger.severe("Error while stopping server ${it.cloudServer!!.serviceId.toName()}", e1)
                    }
                }
            }
        }
        actions.joinAll()
    }

    /**
     * Gets the next id for a server with the given configuration template
     * To make sure that the id is unique, lock the idLock
     */
    private suspend fun getIdForServer(configuration: ICloudConfigurationTemplate): Int {
        val usedIds = serverRepository.getRegisteredServers()
            .filter { it.configurationTemplate.name == configuration.name }
            .map { it.id }
        var i = 1
        while (usedIds.contains(i)) {
            i++
        }
        return i
    }

    private suspend fun loadServerData(configurationTemplate: ICloudConfigurationTemplate): Pair<StartDataSnapshot, StartResult?> {
        val snapshotData = StartDataSnapshot.of(configurationTemplate)
        val dataResult = snapshotData.loadData(
            serverVersionRepository,
            serverVersionTypeRepository,
            javaVersionRepository,
            nodeRepository,
            hostingId
        )
        if (dataResult != null) return snapshotData to dataResult
        if (!snapshotData.version.used) {
            snapshotData.version.used = true
            serverVersionRepository.updateVersion(snapshotData.version)
        }
        return snapshotData to null
    }

    private suspend fun canStartOnNode(
        node: CloudNode,
        configurationTemplate: ICloudConfigurationTemplate
    ): StartResult? {
        // check if the node is allowed to start the server
        if (!configurationTemplate.nodeIds.contains(node.serviceId) && configurationTemplate.nodeIds.isNotEmpty()) {
            return NodeIsNotAllowedStartResult()
        }

        // check if the node has enough ram
        if (node.currentMemoryUsage + configurationTemplate.maxMemory > node.maxMemory) {
            return NotEnoughRamOnNodeStartResult()
        }

        val servers = serverRepository.getRegisteredServers()

        // Check how many servers of the template are already started and cancel if the configured globally total amount is reached
        val startAmountOfTemplate =
            servers.filter { !it.hidden }
                .filter { it.configurationTemplate.uniqueId == configurationTemplate.uniqueId }
                .count { it.state != CloudServerState.STOPPED }
        if (startAmountOfTemplate >= configurationTemplate.maxStartedServices && configurationTemplate.maxStartedServices != -1) {
            return TooMuchServicesOfTemplateStartResult()
        }

        // Check how many servers of the template are already started on this node and cancel if the configured node total amount is reached
        val startedAmountOfTemplateOnNode =
            servers.filter { it.hostNodeId == node.serviceId }
                .filter { !it.hidden }
                .filter { it.state != CloudServerState.STOPPED }
                .count { it.configurationTemplate.uniqueId == configurationTemplate.uniqueId }
        if (startedAmountOfTemplateOnNode >= configurationTemplate.maxStartedServicesPerNode && configurationTemplate.maxStartedServicesPerNode != -1) {
            return TooMuchServicesOfTemplateOnNodeStartResult()
        }
        return null
    }
}