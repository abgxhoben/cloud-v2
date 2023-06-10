package dev.redicloud.service.node.commands

import dev.redicloud.commands.api.*
import dev.redicloud.console.animation.impl.line.AnimatedLineAnimation
import dev.redicloud.console.commands.ConsoleActor
import dev.redicloud.repository.node.CloudNode
import dev.redicloud.repository.node.NodeRepository
import dev.redicloud.repository.server.ServerRepository
import dev.redicloud.service.base.repository.pingService
import dev.redicloud.service.base.suggester.ConnectedCloudNodeSuggester
import dev.redicloud.service.node.NodeService
import dev.redicloud.service.node.repository.suspendNode
import dev.redicloud.utils.defaultScope
import dev.redicloud.utils.service.ServiceId
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@Command("cluster")
@CommandDescription("All commands for the cluster")
class ClusterCommand(private val nodeService: NodeService) : CommandBase() {

    @CommandSubPath("nodes")
    @CommandAlias(["list", "info"])
    @CommandDescription("List all nodes")
    fun list(actor: ConsoleActor) {
        runBlocking {
            try {
                val nodes = nodeService.nodeRepository.getRegisteredNodes()
                actor.sendMessage("§8<====== %hc%§nNodes§8 ======§8>")
                nodes.forEach { node ->
                    actor.sendMessage("")
                    actor.sendMessage("§8> §a${if (node.master) node.getIdentifyingName() + " §8(§6master§8)" else node.getIdentifyingName()}")
                    actor.sendMessage("   - Status§8: %hc%${
                        if (node.isSuspended()) "§4● §8(§fsuspended§8)"
                        else if (node.isConnected()) "§2● §8(§fconnected§8)"
                        else "§c● §8(§fdisconnected§8)"
                    }")
                    actor.sendMessage("   - IP§8: %hc%${node.currentOrLastsession()?.ipAddress ?: "Unknown"}")
                    if (node.isConnected()) {
                        sendPingMessage(node, actor, "   - Ping§8: ")
                        val server = node.getHostedServers().mapNotNull { nodeService.serverRepository.getServer(it)?.name }
                        actor.sendMessage("   - Server§8: %hc%${if (server.isEmpty()) "None" else server.joinToString(", ")}")
                    }
                }
                actor.sendMessage("")
                actor.sendMessage("§8<====== %hc%§nNodes§8 ======§8>")
            }catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @CommandSubPath("ping")
    @CommandDescription("Ping a node")
    fun ping(
        actor: ConsoleActor,
        @CommandParameter("node", true, ConnectedCloudNodeSuggester::class) node: CloudNode
    ) {
        sendPingMessage(node, actor, "${node.getIdentifyingName()} §8> §7")
    }

    private val suspendConfirm = mutableListOf<ServiceId>()
    @CommandSubPath("suspend")
    @CommandDescription("Suspend a node")
    fun suspend(
        actor: ConsoleActor,
        @CommandParameter("node", true, ConnectedCloudNodeSuggester::class) node: CloudNode
    ) {
        runBlocking {
            if (suspendConfirm.contains(node.serviceId)) {
                actor.sendMessage("Suspending node ${node.getIdentifyingName()}...")
                nodeService.nodeRepository.suspendNode(nodeService, node.serviceId)
                return@runBlocking
            }
            actor.sendMessage("§8<====== %hc%§nSuspend§8 ======§8>")
            actor.sendMessage("")
            actor.sendMessage("Node§8: %hc%${node.getIdentifyingName()}")
            actor.sendMessage("Servers§8: %hc%${node.getHostedServers().mapNotNull { nodeService.serverRepository.getServer(it)?.name }.joinToString(", ")}")
            sendPingMessage(node, actor, "Ping§8: %hc%")
            actor.sendMessage("")
            actor.sendMessage("§cThis will suspend the node and all hosted servers will be stopped!")
            actor.sendMessage("§cEnter the command again to confirm within 10 seconds")
            actor.sendMessage("")
            actor.sendMessage("§8<====== %hc%§nSuspend§8 ======§8>")
            suspendConfirm.add(node.serviceId)
        }
    }

    private fun sendPingMessage(node: CloudNode, actor: ConsoleActor, prefix: String, block: (Long) -> Unit = {}) {
        var ping = -2L
        val local = node.serviceId == nodeService.nodeRepository.serviceId
        var cancel = false
        val pingAnimation = AnimatedLineAnimation(actor.console, 200) {
            if (cancel) {
                null
            }else if(local){
                cancel = true
                "$prefix%hc%this node"
            }else if (ping == -2L) {
                "$prefix%hc%%loading% §8(%tc%pinging§8)"
            } else if (ping == -1L) {
                cancel = true
                "$prefix%hc%§cping time outed"
            } else {
                cancel = true
                "$prefix%hc%$ping%tc%ms"
            }
        }
        actor.console.startAnimation(pingAnimation)
        if (local) return
        defaultScope.launch {
            delay(1500)
            ping = nodeService.nodeRepository.pingService(node.serviceId)
            block(ping)
        }
    }

}