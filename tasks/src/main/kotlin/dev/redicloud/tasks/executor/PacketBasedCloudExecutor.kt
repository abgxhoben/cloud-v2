package dev.redicloud.tasks.executor

import dev.redicloud.event.CloudEvent
import dev.redicloud.event.EventManager
import dev.redicloud.event.InlineEventCaller
import dev.redicloud.packets.AbstractPacket
import dev.redicloud.packets.PacketListener
import dev.redicloud.packets.PacketManager
import dev.redicloud.tasks.CloudTask
import kotlin.reflect.KClass

class PacketBasedCloudExecutor(
    task: CloudTask,
    val packetManager: PacketManager,
    val packets: List<KClass<out AbstractPacket>>
) : CloudTaskExecutor(task) {

    private val listeners = mutableListOf<PacketListener<*>>()

    override suspend fun run() {
        packets.forEach { listener(it) }
        onFinished {
            listeners.forEach {
                packetManager.unregisterListener(it)
            }
        }
    }

    private fun listener(clazz: KClass<out AbstractPacket>) {
        val listener = packetManager.listen(clazz) {
            cloudTask.preExecute(this)
        }
        listeners.add(listener)
    }

}