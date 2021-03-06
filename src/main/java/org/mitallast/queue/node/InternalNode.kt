package org.mitallast.queue.node

import com.google.inject.AbstractModule
import com.google.inject.Injector
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.apache.logging.log4j.MarkerManager
import org.mitallast.queue.common.component.AbstractLifecycleComponent
import org.mitallast.queue.common.component.ComponentModule
import org.mitallast.queue.common.component.LifecycleService
import org.mitallast.queue.common.component.ModulesBuilder
import org.mitallast.queue.common.events.EventBusModule
import org.mitallast.queue.common.file.FileModule
import org.mitallast.queue.common.json.JsonModule
import org.mitallast.queue.common.logging.LoggingModule
import org.mitallast.queue.common.logging.LoggingService
import org.mitallast.queue.common.netty.NettyModule
import org.mitallast.queue.crdt.CrdtModule
import org.mitallast.queue.crdt.rest.RestCrdtModule
import org.mitallast.queue.raft.RaftModule
import org.mitallast.queue.raft.rest.RaftRestModule
import org.mitallast.queue.rest.RestModule
import org.mitallast.queue.security.SecurityModule
import org.mitallast.queue.transport.TransportModule

class InternalNode constructor(conf: Config, logging: LoggingService, vararg plugins: AbstractModule) : AbstractLifecycleComponent(logging), Node {

    private val config = conf.withFallback(ConfigFactory.defaultReference())
    private val injector: Injector

    init {
        logger.info("initializing...")

        val modules = ModulesBuilder()
        modules.add(LoggingModule(logging))
        modules.add(ComponentModule(config, logging))
        modules.add(FileModule())
        modules.add(JsonModule())
        modules.add(EventBusModule())
        modules.add(SecurityModule())
        modules.add(NettyModule())
        modules.add(TransportModule())
        if (config.getBoolean("rest.enabled")) {
            modules.add(RestModule())
        }
        if (config.getBoolean("raft.enabled")) {
            modules.add(RaftModule())
            if (config.getBoolean("rest.enabled")) {
                modules.add(RaftRestModule())
            }
            if (config.getBoolean("crdt.enabled")) {
                modules.add(CrdtModule())
                if (config.getBoolean("rest.enabled")) {
                    modules.add(RestCrdtModule())
                }
            }
        }

        modules.add(*plugins)
        injector = modules.createInjector()

        logger.info("initialized")
    }

    override fun config(): Config = config

    override fun injector(): Injector = injector

    override fun doStart() {
        injector.getInstance(LifecycleService::class.java).start()
    }

    override fun doStop() {
        injector.getInstance(LifecycleService::class.java).stop()
    }

    override fun doClose() {
        injector.getInstance(LifecycleService::class.java).close()
    }

    companion object {
        fun build(config: Config, vararg plugins: AbstractModule): InternalNode {
            val configWithFallback = config.withFallback(ConfigFactory.defaultReference())
            val port = configWithFallback.getString("raft.discovery.port")
            val marker = MarkerManager.getMarker(":$port")
            val logging = LoggingService(marker)
            return InternalNode(configWithFallback, logging, *plugins)
        }
    }
}
