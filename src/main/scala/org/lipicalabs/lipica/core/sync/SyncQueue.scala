package org.lipicalabs.lipica.core.sync

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

import org.lipicalabs.lipica.core.kernel._
import org.lipicalabs.lipica.core.config.SystemProperties
import org.lipicalabs.lipica.core.db.datasource.mapdb.MapDBFactoryImpl
import org.lipicalabs.lipica.core.db.{BlockQueueImpl, HashStoreImpl, BlockQueue, HashStore}
import org.lipicalabs.lipica.core.facade.manager.WorldManager
import org.lipicalabs.lipica.core.utils.{ErrorLogger, CountingThreadFactory, ImmutableBytes}
import org.lipicalabs.lipica.core.validator.BlockHeaderValidator
import org.slf4j.LoggerFactory

/**
 * 同期処理実行時にハッシュ値とブロックとを保管し、
 * 逐次的に処理する中核的なクラスです。
 *
 * 自ノード全体で１インスタンスのみ生成され、動作します。
 *
 * Created by IntelliJ IDEA.
 * 2015/12/10 20:17
 * YANAGISAWA, Kentaro
 */
class SyncQueue {
	import SyncQueue._

	/**
	 * ノード単位でグローバルなシングルトンコンポーネント類。
	 */
	private def worldManager: WorldManager = WorldManager.instance
	private def blockchain: Blockchain = worldManager.blockchain
	private def syncManager: SyncManager = worldManager.syncManager
	private def headerValidator: BlockHeaderValidator = worldManager.blockHeaderValidator

	/**
	 * 他ノードから受領したブロックハッシュ値を格納する保管庫です。
	 */
	private val hashStoreRef: AtomicReference[HashStore] = new AtomicReference[HashStore](null)
	private def hashStore: HashStore = this.hashStoreRef.get

	/**
	 * 他ノードから受領したブロックを、ブロック番号の昇順にソートして格納し、取り出すキューです。
	 */
	private val blockQueueRef: AtomicReference[BlockQueue] = new AtomicReference[BlockQueue](null)
	private def blockQueue: BlockQueue = this.blockQueueRef.get

	def init(): Unit = {
		logger.info("<SyncQueue> Start loading sync queue.")
		val mapDBFactory = new MapDBFactoryImpl
		//HashStoreの生成および初期化を実行する。
		val hs = new HashStoreImpl(mapDBFactory)
		hs.open()
		this.hashStoreRef.set(hs)
		//BlockQueueの生成および初期化を実行する。
		val bq = new BlockQueueImpl(mapDBFactory)
		bq.open()
		this.blockQueueRef.set(bq)

		//キューからブロックを取り出してブロックチェーンにつなげようとする処理タスクを生成する。
		val task = new Runnable {
			override def run(): Unit = consumeQueue()
		}
		//単一の常駐スレッド上で実行する。
		Executors.newSingleThreadExecutor(new CountingThreadFactory("sync-queue")).execute(task)
	}

	/**
	 * ブロックキューから最小番号のブロックを取り出して、
	 * それを自ノードのブロックチェーンに連結することを継続的に試みます。
	 */
	private def consumeQueue(): Unit = {
		while (true) {
			try {
				val blockWrapper = this.blockQueue.take
				logger.info("<SyncQueue> BlockQueue size=%,d".format(this.blockQueue.size))

				//
				//ブロックチェーンに連結しようとする。
				//
				val importResult = this.blockchain.tryToConnect(blockWrapper.block)

				if (importResult == ImportResult.NoParent) {
					//このブロックの親がチェーンにない。つまりブロック番号ギャップである。
					logger.info("<SyncQueue> No parent on the chain for Block[%,d] (Hash=%s)".format(blockWrapper.blockNumber, blockWrapper.block.shortHash))
					blockWrapper.fireImportFailed()
					//必要であれば、他ノードからギャップ部分のブロックを優先的に取得しようとする。
					this.syncManager.tryGapRecovery(blockWrapper)
					//再試行対象としてキューに戻す。ソートされて、キューの先頭（＝次にtakeされる対象）になるはず。
					this.blockQueue.add(blockWrapper)
					//いまダメだったばかりなので、しばらく待たなければ成功するはずはない。
					Thread.sleep(2000L)
				}
				if (blockWrapper.isNewBlock && importResult.isSuccessful) {
					//最近生成されたブロックを連結できたということは、同期状態は良好ということだ。
					this.syncManager.notifyNewBlockImported(blockWrapper)
				}
				if (importResult == ImportResult.ImportedBest) {
					//ブロックチェーンを伸ばすことに成功。
					logger.info("<SyncQueue> Success importing BEST: BlockNumber=%,d & BlockHash=%s, Tx.size=%,d".format(blockWrapper.blockNumber, blockWrapper.block.shortHash, blockWrapper.block.transactions.size))
				}
				if (importResult == ImportResult.ImportedNotBest) {
					//ブロックチェーンに、傍系のおじさんが追加された。
					logger.info("<SyncQueue> Success importing NOT_BEST: BlockNumber=%,d & BlockHash=%s, Tx.size=%,d".format(blockWrapper.blockNumber, blockWrapper.block.shortHash, blockWrapper.block.transactions.size))
				}
			} catch {
				case e: Throwable =>
					//ループの外に例外を突き抜けさせない。
					ErrorLogger.logger.warn("<SyncQueue> Exception caught: %s".format(e.getClass.getSimpleName), e)
					logger.warn("<SyncQueue> Exception caught: %s".format(e.getClass.getSimpleName), e)
			}
		}
	}

	/**
	 * 渡されたブロックを、ブロックキューに追加します。
	 */
	def addBlocks(blocks: Seq[Block], nodeId: ImmutableBytes): Unit = {
		if (blocks.isEmpty) {
			return
		}
		//エラーブロックが存在したら、インポートしない。
		blocks.find(each => !isValid(each.blockHeader)).foreach {
			found => {
				if (logger.isDebugEnabled) {
					logger.debug("<SyncQueue> Invalid block: %s".format(found.toString))
				}
				syncManager.reportInvalidBlock(nodeId)
				return
			}
		}
		if (logger.isDebugEnabled) {
			logger.debug("<SyncQueue> Adding %,d blocks from [%,d  %s]".format(blocks.size, blocks.head.blockNumber, blocks.head.hash))
		}
		//ラップし、キューに追加する。
		val wrappers = blocks.map(each => BlockWrapper(each, nodeId))
		this.blockQueue.addAll(wrappers)
		if (logger.isDebugEnabled) {
			logger.debug("<SyncQueue> Added %,d blocks [%,d %s...] - [%,d %s...]. Queue.Size=%,d".format(
				blocks.size, blocks.head.blockNumber, blocks.head.shortHash, blocks.last.blockNumber, blocks.last.shortHash, this.blockQueue.size
			))
		}
	}

	/**
	 * 渡された新たなブロックをキューに追加します。
	 */
	def addNewBlock(block: Block, nodeId: ImmutableBytes): Unit = {
		if (!isValid(block.blockHeader)) {
			this.syncManager.reportInvalidBlock(nodeId)
			return
		}
		val wrapper = BlockWrapper(block, newBlock = true, nodeId = nodeId)
		wrapper.receivedAt = System.currentTimeMillis
		this.blockQueue.add(wrapper)

		if (logger.isDebugEnabled) {
			logger.debug("<SyncQueue> Added a block [%,d %s...]. Queue.Size=%,d".format(
				block.blockNumber, block.shortHash, this.blockQueue.size
			))
		}
	}

	/**
	 * 渡されたハッシュ値を保存します。
	 * 保存されたハッシュ値は、ブロック取得において利用されます。
	 */
	def addHash(hash: ImmutableBytes): Unit = {
		this.hashStore.addFirst(hash)
		if (logger.isTraceEnabled) {
			logger.trace("<SyncQueue> Adding hash to a hashQueue: %s, hashQueue.size=%,d".format(hash.toShortString, this.hashStore.size))
		}
	}

	/**
	 * 渡されたハッシュ値を保存します。
	 * 保存されたハッシュ値は、ブロック取得において利用されます。
	 */
	def addHashes(hashes: Seq[ImmutableBytes]): Unit = {
		val filtered = this.blockQueue.excludeExisting(hashes)
		this.hashStore.addBatchFirst(filtered)
		if (logger.isDebugEnabled) {
			logger.debug("<SyncQueue> %,d hashes filtered out, %,d added.".format(hashes.size - filtered.size, filtered.size))
		}
	}

	/**
	 * 渡されたハッシュ値を保存します。
	 * 保存されたハッシュ値は、ブロック取得において利用されます。
	 */
	def addHashesLast(hashes: Seq[ImmutableBytes]): Unit = {
		val filtered = this.blockQueue.excludeExisting(hashes)
		this.hashStore.addBatch(filtered)
		if (logger.isDebugEnabled) {
			logger.debug("<SyncQueue> %,d hashes filtered out, %,d added.".format(hashes.size - filtered.size, filtered.size))
		}
	}

	/**
	 * 渡されたハッシュ値を保存します。
	 * 保存されたハッシュ値は、ブロック取得において利用されます。
	 */
	def addNewBlockHashes(hashes: Seq[ImmutableBytes]): Unit = {
		val notInQueue = this.blockQueue.excludeExisting(hashes)
		val notInChain = notInQueue.filter(each => !this.blockchain.existsBlock(each))
		this.hashStore.addBatch(notInChain)
	}

	/**
	 * 一度フロントエンドに払いだしたハッシュ値について、返品を受け付けます。
	 */
	def returnHashes(hashes: Seq[ImmutableBytes]): Unit = {
		if (hashes.isEmpty) {
			return
		}
		logger.info("<SyncQueue> Hashes remained uncovered: hashes.size=%,d".format(hashes.size))
		hashes.reverse.foreach {
			each => {
				if (logger.isDebugEnabled) {
					logger.debug("<SyncQueue> Putting hash: %s".format(each))
				}
				this.hashStore.addFirst(each)
			}
		}
	}

	/**
	 * 設定によって決められた個数のハッシュ値を、
	 * ブロック取得のために払い出します。
	 */
	def pollHashes: Seq[ImmutableBytes] = this.hashStore.pollBatch(SystemProperties.CONFIG.maxBlocksAsk)

	def logHashQueueSize(): Unit = logger.info("<SyncQueue> Block hashes list size = %,d".format(this.hashStore.size))

	/**
	 * BlockQueue に保管されているブロックの数を返します。
	 */
	def blockQueueSize: Int = this.blockQueue.size

	/**
	 * HashStore に保管されているハッシュ値の数を返します。
	 */
	def hashStoreSize: Int = this.hashStore.size

	/**
	 * 保管されているブロックおよびハッシュ値の情報をすべて消去します。
	 */
	def clear(): Unit = {
		this.hashStore.clear()
		this.blockQueue.clear()
	}

	def isHashesEmpty: Boolean = this.hashStore.isEmpty

	def isBlocksEmpty: Boolean = this.blockQueue.isEmpty

	def clearHashes(): Unit = this.hashStore.clear()

	def hasSolidBlocks: Boolean = this.blockQueue.peek.exists(_.isSolidBlock)

	private def isValid(header: BlockHeader): Boolean = {
		if (!this.headerValidator.validate(header)) {
			if (logger.isWarnEnabled) {
				this.headerValidator.logErrors(logger)
			}
			false
		} else {
			true
		}
	}

}

object SyncQueue {
	private val logger = LoggerFactory.getLogger("sync")
}