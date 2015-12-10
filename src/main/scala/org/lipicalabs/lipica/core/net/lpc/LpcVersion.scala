package org.lipicalabs.lipica.core.net.lpc

/**
 * Created by IntelliJ IDEA.
 * 2015/11/30 20:30
 * YANAGISAWA, Kentaro
 */
sealed trait LpcVersion {
	def code: Int
}

object LpcVersion {
	val SupportedVersions: Seq[Int] = Seq(V0.code)
}

case object V0 extends LpcVersion {
	override val code: Int = 61//TODO
}