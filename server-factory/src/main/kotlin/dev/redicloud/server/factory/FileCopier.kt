package dev.redicloud.server.factory

import dev.redicloud.logging.LogManager
import dev.redicloud.repository.server.CloudServer
import dev.redicloud.repository.server.version.CloudServerVersion
import dev.redicloud.repository.server.version.CloudServerVersionRepository
import dev.redicloud.repository.server.version.CloudServerVersionType
import dev.redicloud.repository.server.version.CloudServerVersionTypeRepository
import dev.redicloud.repository.server.version.handler.IServerVersionHandler
import dev.redicloud.repository.template.file.FileTemplate
import dev.redicloud.repository.template.file.AbstractFileTemplateRepository
import dev.redicloud.utils.CLOUD_VERSION
import dev.redicloud.utils.CONNECTORS_FOLDER
import dev.redicloud.utils.STATIC_FOLDER
import dev.redicloud.utils.TEMP_SERVER_FOLDER
import dev.redicloud.utils.service.ServiceId
import dev.redicloud.utils.service.ServiceType
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.*


class FileCopier(
    serverProcess: ServerProcess,
    cloudServer: CloudServer,
    serverVersionRepository: CloudServerVersionRepository,
    private val serverVersionTypeRepository: CloudServerVersionTypeRepository,
    fileTemplateRepository: AbstractFileTemplateRepository
) {

    val serverUniqueId = UUID.randomUUID()
    val serviceId: ServiceId
    val configurationTemplate = serverProcess.configurationTemplate
    val serverVersion: CloudServerVersion
    val serverVersionType: CloudServerVersionType
    val templates: List<FileTemplate>
    val workDirectory: File
    private val logger = LogManager.logger(FileCopier::class)

    init {
        if (configurationTemplate.serverVersionId == null) throw IllegalStateException("Server version is not set for configuration template ${configurationTemplate.name}!")
        // get server version
        serverVersion = runBlocking { serverVersionRepository.getVersion(configurationTemplate.serverVersionId!!) }
            ?: throw IllegalStateException("Server version is not set for configuration template ${configurationTemplate.name}!")
        if (serverVersion.typeId == null) throw IllegalStateException("Server version type is not set for server version ${serverVersion.getDisplayName()}!")
        serverVersionType = runBlocking { serverVersionTypeRepository.getType(serverVersion.typeId!!) }
            ?: throw IllegalStateException("Server version type is not set for server version ${serverVersion.getDisplayName()}!")
        serviceId = ServiceId(serverUniqueId, if (serverVersionType.proxy) ServiceType.PROXY_SERVER else ServiceType.MINECRAFT_SERVER)
        // get templates by given configuration template and collect also inherited templates
        templates = configurationTemplate.fileTemplateIds.mapNotNull { runBlocking { fileTemplateRepository.getTemplate(it) } }
            .flatMap { runBlocking { fileTemplateRepository.collectTemplates(it) } }
        // create work directory
        workDirectory = if(configurationTemplate.static) {
            File(STATIC_FOLDER.getFile().absolutePath, "${cloudServer.name}-${serverUniqueId}")
        }else {
            File(TEMP_SERVER_FOLDER.getFile().absolutePath, "${cloudServer.name}-${serverUniqueId}")
        }
        if (!workDirectory.exists()) workDirectory.mkdirs()
    }

    suspend fun copyConnector() {
        logger.fine("Copying connector for $serviceId")
        CONNECTORS_FOLDER.createIfNotExists()
        val connectorFile = File(CONNECTORS_FOLDER.getFile(), serverVersionType.connectorPluginName.replace("%cloud_version%", CLOUD_VERSION))
        if (!connectorFile.exists()) {
            connectorFile.createNewFile()
            if (serverVersionType.connectorDownloadUrl == null) {
                logger.warning("Connector download url for ${serverVersionType.name} is not set! The server will not connect to the cloud cluster!")
                logger.warning("You can set the connector download url in the server version type settings with: 'svt edit <name> connector url <url>'")
                return
            }
            try {
                serverVersionTypeRepository.downloadConnector(serverVersionType)
                if (!connectorFile.exists()) {
                    logger.warning("Connector file for ${serverVersionType.name} does not exist! The server will not connect to the cloud cluster!")
                    logger.warning("You can set the connector file in the server version type settings with: 'svt edit <name> connector jar <connector>'")
                    return
                }
            }catch (e: Exception) {
                logger.warning("Failed to download connector for ${serverVersionType.name} from ${serverVersionType.connectorDownloadUrl}", e)
                logger.warning("The server will not connect to the cloud cluster!")
                logger.warning("You can set the connector download url in the server version type settings with: 'svt edit <name> connector url <url>'")
                return
            }
        }
        val pluginFolder = File(workDirectory, serverVersionType.connectorFolder)
        if (!pluginFolder.exists()) pluginFolder.mkdirs()
        connectorFile.copyRecursively(File(pluginFolder, connectorFile.name))
    }

    /**
     * Copies all files for the server version to the work directory
     */
    suspend fun copyVersionFiles(action: (String) -> String = { it }) {
        logger.fine("Copying files for $serviceId of version ${serverVersion.getDisplayName()}")
        val versionHandler = IServerVersionHandler.getHandler(serverVersionType)
        versionHandler.getLock(serverVersion).lock()
        try {
            if (!versionHandler.isPatched(serverVersion) && versionHandler.isPatchVersion(serverVersion)) {
                versionHandler.patch(serverVersion)
            }else if(!versionHandler.isDownloaded(serverVersion)) {
                versionHandler.download(serverVersion)
            }
            versionHandler.getFolder(serverVersion).copyRecursively(workDirectory)
            serverVersionType.doFileEdits(workDirectory, action)
            serverVersion.doFileEdits(workDirectory, action)
        }finally {
            versionHandler.getLock(serverVersion).unlock()
        }
    }

    /**
     * Copies all templates to the work directory
     */
    suspend fun copyTemplates() {
        logger.fine("Copying templates for $serviceId")
        templates.forEach {
            it.getFolder().copyRecursively(workDirectory)
        }
    }

}