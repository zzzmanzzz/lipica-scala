package org.lipicalabs.lipica.core.net.server

import java.util

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.socket.DatagramPacket
import io.netty.handler.codec.MessageToMessageDecoder
import org.lipicalabs.lipica.core.net.transport.TransportMessage
import org.lipicalabs.lipica.core.net.transport.discover.DiscoveryEvent

/**
 * UDPListener で netty のハンドラパイプラインに登録されて、
 * ネットワークと NodeManager との橋渡し役を務めるクラスです。
 *
 * 受信したUDPデータグラムをメッセージに変換して netty のパイプラインに渡します。
 * （つまり、MessageHandlerの逆側です。）
 *
 * Created by IntelliJ IDEA.
 * 2015/12/22 15:47
 * YANAGISAWA, Kentaro
 */
class PacketDecoder extends MessageToMessageDecoder[DatagramPacket] {

	override def decode(ctx: ChannelHandlerContext, packet: DatagramPacket, out: util.List[AnyRef]): Unit = {
		val buf = packet.content
		val encoded = new Array[Byte](buf.readableBytes)
		buf.readBytes(encoded)
		val message: TransportMessage = TransportMessage.decode(encoded)

		//println("Received: %s %s from %s".format(message.messageType, ImmutableBytes(encoded), packet.sender))

		val event = new DiscoveryEvent(message, packet.sender)
		out.add(event)
	}

}