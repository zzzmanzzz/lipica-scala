package org.lipicalabs.lipica.core.kernel

import org.lipicalabs.lipica.core.crypto.digest.DigestValue
import org.lipicalabs.lipica.core.kernel.genesis.GenesisLoader
import org.lipicalabs.lipica.core.utils.ImmutableBytes

/**
 * Genesisブロックを表現するクラスです。
 *
 * Created by IntelliJ IDEA.
 * @since 2015/11/21 11:56
 * @author YANAGISAWA, Kentaro
 */
class Genesis private[kernel](_header: BlockHeader, val premine: Map[Address, AccountState]) extends PlainBlock(_header, Seq.empty[TransactionLike], Seq.empty[BlockHeader]) {
	//
}

object Genesis {

	/**
	 * Genesisブロックのインスタンスを返します。
	 * （可変の要素が多々あるので、毎回ロードする。）
	 */
	def getInstance: Genesis = GenesisLoader.loadGenesisBlock

	def getInstance(genesisFileName: String): Genesis = GenesisLoader.loadGenesisBlock(genesisFileName)

	val GenesisHash: DigestValue = getInstance.hash

}