package org.lipicalabs.lipica.core.net.endpoint

import java.net.InetSocketAddress

import io.netty.bootstrap.Bootstrap
import io.netty.channel.{DefaultMessageSizeEstimator, ChannelOption}
import io.netty.channel.socket.nio.NioSocketChannel
import org.lipicalabs.lipica.core.concurrent.ExecutorPool
import org.lipicalabs.lipica.core.config.NodeProperties
import org.lipicalabs.lipica.core.facade.components.ComponentsMotherboard
import org.lipicalabs.lipica.core.net.channel.LipicaChannelInitializer
import org.lipicalabs.lipica.core.net.peer_discovery.NodeId
import org.lipicalabs.lipica.core.utils.ErrorLogger
import org.slf4j.LoggerFactory

/**
 * クライアントとしてTCP接続を確立するクラスです。
 *
 * Created by IntelliJ IDEA.
 * 2015/12/13 15:42
 * YANAGISAWA, Kentaro
 */
class PeerClient {
	import PeerClient._

	private def componentsMotherboard: ComponentsMotherboard = ComponentsMotherboard.instance

	/**
	 * 他ノードに対して接続確立を試行します。
	 *
	 * @param address 接続先アドレス。
	 * @param nodeId 接続先ノードID。
	 */
	def connect(address: InetSocketAddress, nodeId: NodeId): Unit = connect(address, nodeId, discoveryMode = false)

	/**
	 * 他ノードに対して接続確立を試行します。
	 */
	def connect(address: InetSocketAddress, nodeId: NodeId, discoveryMode: Boolean): Unit = {
		this.componentsMotherboard.listener.trace("<PeerClient> Connecting to %s".format(address))
		val channelInitializer = new LipicaChannelInitializer(nodeId)
		channelInitializer.peerDiscoveryMode = discoveryMode

		try {
			val b = (new Bootstrap).group(ExecutorPool.instance.clientGroup).channel(classOf[NioSocketChannel]).
				option(ChannelOption.SO_KEEPALIVE, java.lang.Boolean.TRUE).
				option(ChannelOption.MESSAGE_SIZE_ESTIMATOR, DefaultMessageSizeEstimator.DEFAULT).
				option(ChannelOption.CONNECT_TIMEOUT_MILLIS, java.lang.Integer.valueOf(NodeProperties.instance.connectionTimeoutMillis)).
				remoteAddress(address).
				handler(channelInitializer)
			//クライアントとして接続する。
			val future = b.connect().sync()
			logger.debug("<PeerClient> Connection is established to %s %s.".format(nodeId.toShortString, address))
			//接続がクローズされるまで待つ。
			future.channel().closeFuture().sync()
			logger.debug("<PeerClient> Connection is closed to %s %s.".format(nodeId.toShortString, address))
		} catch {
			case e: Throwable =>
				if (discoveryMode) {
					logger.debug("<PeerClient> Exception caught: %s connecting to %s...(%s)".format(e.getClass.getSimpleName, nodeId.toShortString, address), e)
				} else {
					ErrorLogger.logger.warn("<PeerClient> Exception caught: %s connecting to %s (%s)".format(e.getClass.getSimpleName, nodeId.toShortString, address), e)
					logger.warn("<PeerClient> Exception caught: %s connecting to %s (%s)".format(e.getClass.getSimpleName, nodeId.toShortString, address), e)
				}
		}
	}

}

object PeerClient {
	private val logger = LoggerFactory.getLogger("net")
}
