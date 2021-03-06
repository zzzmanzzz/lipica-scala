package org.lipicalabs.lipica.core.net.lpc.message

import org.lipicalabs.lipica.core.kernel.{Transaction, TransactionLike}
import org.lipicalabs.lipica.core.net.lpc.LpcMessageCode
import org.lipicalabs.lipica.core.bytes_codec.RBACCodec
import org.lipicalabs.lipica.core.utils.ImmutableBytes

/**
 * トランザクションの集合を提供するためのメッセージです。
 *
 * Created by IntelliJ IDEA.
 * 2015/12/09 21:07
 * YANAGISAWA, Kentaro
 */
case class TransactionsMessage(transactions: Seq[TransactionLike]) extends LpcMessage {

	override def toEncodedBytes = {
		val seq = this.transactions.map(each => each.toEncodedBytes)
		RBACCodec.Encoder.encodeSeqOfByteArrays(seq)
	}

	override def code = LpcMessageCode.Transactions.asByte

	override def toString: String = "TransactionsMessage(%,d txs)".format(this.transactions.size)
}

object TransactionsMessage {
	def decode(encodedBytes: ImmutableBytes): TransactionsMessage = {
		val items = RBACCodec.Decoder.decode(encodedBytes).right.get.items
		val transactions = items.map(each => Transaction.decode(each))
		new TransactionsMessage(transactions)
	}
}