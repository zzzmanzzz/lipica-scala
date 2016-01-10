package org.lipicalabs.lipica.core.kernel

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import org.lipicalabs.lipica.core.config.SystemProperties
import org.lipicalabs.lipica.core.crypto.digest.DigestUtils
import org.lipicalabs.lipica.core.db.datasource.{DataSourcePool, KeyValueDataSource}
import org.lipicalabs.lipica.core.trie.SecureTrie
import org.lipicalabs.lipica.core.bytes_codec.RBACCodec
import org.lipicalabs.lipica.core.utils.ImmutableBytes
import org.lipicalabs.lipica.core.vm.DataWord
import org.slf4j.LoggerFactory

import scala.collection.mutable

/**
 * ContractDetails の実装クラスです。
 *
 * コントラクトの内容である、コードやストレージデータを保持します。
 *
 *
 * @since 2015/11/08
 * @author YANAGISAWA, Kentaro
 */
class ContractDetailsImpl() extends ContractDetails {

	import ContractDetailsImpl._

	private val addressRef: AtomicReference[ImmutableBytes] = new AtomicReference[ImmutableBytes](ImmutableBytes.empty)
	override def address: ImmutableBytes = this.addressRef.get
	override def address_=(v: ImmutableBytes) = this.addressRef.set(v)

	private val codeRef: AtomicReference[ImmutableBytes] = new AtomicReference[ImmutableBytes](ImmutableBytes.empty)
	override def code = this.codeRef.get
	override def code_=(v: ImmutableBytes) = this.codeRef.set(v)

	private val keysRef = new AtomicReference[mutable.HashSet[ImmutableBytes]](new mutable.HashSet[ImmutableBytes])
	private def keys: mutable.HashSet[ImmutableBytes] = this.keysRef.get

	private val storageTrieRef: AtomicReference[SecureTrie] = new AtomicReference[SecureTrie](SecureTrie.newInstance)
	private def storageTrie: SecureTrie = this.storageTrieRef.get

	private val isDirtyRef: AtomicBoolean = new AtomicBoolean(false)
	override def isDirty = this.isDirtyRef.get
	override def isDirty_=(v: Boolean) = this.isDirtyRef.set(v)

	private val isDeletedRef: AtomicBoolean = new AtomicBoolean(false)
	override def isDeleted: Boolean = this.isDeletedRef.get
	override def isDeleted_=(v: Boolean): Unit = this.isDeletedRef.set(v)

	private val useExternalStorageRef: AtomicBoolean = new AtomicBoolean(false)
	def useExternalStorage: Boolean = {
		if (this.useExternalStorageRef.get) {
			return true
		}
		SystemProperties.CONFIG.detailsInMemoryStorageLimit < this.keys.size
	}
	//def useExternalStorage_=(v: Boolean): Unit = this.useExternalStorageRef.set(v)

	private val externalStorageDataSourceRef: AtomicReference[KeyValueDataSource] = new AtomicReference[KeyValueDataSource](null)
	def externalStorageDataSource: KeyValueDataSource = {
		if (this.externalStorageDataSourceRef.get eq null) {
			this.externalStorageDataSourceRef.set(DataSourcePool.levelDbByName(dataSourceName))
		}
		this.externalStorageDataSourceRef.get
	}
	def externalStorageDataSource_=(v: KeyValueDataSource): Unit = {
		this.externalStorageDataSourceRef.set(v)
	}

	private def addKey(key: ImmutableBytes): Unit = this.keys.add(key)

	private def removeKey(key: ImmutableBytes): Unit = {
		//this.keys.remove(key)
	}

	def put(key: ImmutableBytes, value: ImmutableBytes): Unit = put(DataWord(key), DataWord(value))

	override def put(key: DataWord, value: DataWord) = {
		if (value == DataWord.Zero) {
			this.storageTrie.delete(key.data)
			removeKey(key.data)
		} else {
			val encodedValue = RBACCodec.Encoder.encode(value.getDataWithoutLeadingZeros)
			this.storageTrie.update(key.data, encodedValue)
			addKey(key.data)
		}
		this.isDirty = true
		//キーの数が上限を超えるようならば、ストレージ用の独立したデータベースを定義しなければならない。
		this.useExternalStorageRef.set(useExternalStorage)
	}

	override def get(key: DataWord): Option[DataWord] = {
		val data = this.storageTrie.get(key.data)
		if (data.nonEmpty) {
			Some(DataWord(RBACCodec.Decoder.decode(data).right.get.bytes))
		} else {
			None
		}
	}

	override def storageRoot: ImmutableBytes = this.storageTrie.rootHash

	override def storageKeys: Set[DataWord] = this.keys.map(DataWord(_)).toSet

	override def put(data: Map[DataWord, DataWord]): Unit = {
		for (entry <- data) {
			put(entry._1, entry._2)
		}
	}

	override def storageContent(aKeys: Iterable[DataWord]) = {
		aKeys.flatMap {
			eachKey => {
				get(eachKey).map(v => (eachKey, v))
			}
		}.toMap
	}

	override def storageContent: Map[DataWord, DataWord] = {
		this.keys.flatMap {
			each => {
				val eachKey = DataWord(each)
				get(eachKey).map(v => (eachKey, v))
			}
		}.toMap
	}

	override def storageSize: Int = this.keys.size

	override def syncStorage(): Unit = {
		if (useExternalStorage) {
			this.storageTrie.cache.setDB(externalStorageDataSource)
			this.storageTrie.sync()

			DataSourcePool.closeDataSource(dataSourceName)
		}
	}

	private def dataSourceName = "details-storage/" + address.toHexString

	override def getSnapshotTo(hash: ImmutableBytes) = {
		val keyValueDataSource = this.storageTrie.cache.dataSource
		val snapStorage =
			if (hash == DigestUtils.EmptyTrieHash) {
				new SecureTrie(keyValueDataSource)
			} else {
				new SecureTrie(keyValueDataSource, hash)
			}
		snapStorage.cache = this.storageTrie.cache

		val details = ContractDetailsImpl.newInstance(this.address, snapStorage, this.code)
		details.keysRef.set(this.keys)
		//this.keys.foreach(details.keys.add)
		details
	}

	override def createClone: ContractDetails = {
		val result = new ContractDetailsImpl
		result.address = this.address
		result.code = this.code
		this.storageContent.foreach {
			entry => result.put(entry._1, entry._2)
		}
		result
	}

	override def encode: ImmutableBytes = {
		val startTime = System.nanoTime
		val encodedAddress = RBACCodec.Encoder.encode(this.address)
		val encodedIsExternalStorage = RBACCodec.Encoder.encode(this.useExternalStorage)
		val encodedStorageRoot = RBACCodec.Encoder.encode(
			if (this.useExternalStorage) {
				this.storageTrie.rootHash
			} else {
				Array.emptyByteArray
			}
		)
		val encodedStorage = RBACCodec.Encoder.encode(this.storageTrie.serialize)
		val encodedCode = RBACCodec.Encoder.encode(this.code)
		val encodedKeys = RBACCodec.Encoder.encode(this.keys.toSeq)

		val result = RBACCodec.Encoder.encodeSeqOfByteArrays(Seq(encodedAddress, encodedIsExternalStorage, encodedStorage, encodedCode, encodedKeys, encodedStorageRoot))
		val endTime = System.nanoTime

		if (logger.isInfoEnabled) {
			logger.info("<ContractDetails> [%s] Encoding took %,d nanos for %,d entries. (%,d bytes)".format(
				this.address.toShortString, endTime - startTime, this.keys.size, encodedStorage.length
			))
		}
		result
	}

	override def decode(data: ImmutableBytes) = {
		val startTime = System.nanoTime
		val items = RBACCodec.Decoder.decode(data).right.get.items
		this.address = items.head.bytes
		this.useExternalStorageRef.set(items(1).asPositiveLong > 0L)
		this.storageTrie.deserialize(items(2).bytes)
		this.code = items(3).bytes
		items(4).items.foreach {
			each => addKey(each.bytes)
		}
		if (useExternalStorage) {
			this.storageTrie.root = items(5).bytes
			this.storageTrie.cache.setDB(externalStorageDataSource)
		}
		val endTime = System.nanoTime
		if (logger.isInfoEnabled) {
			logger.info("<ContractDetails> [%s] Decoding took %,d nanos for %,d entries. (%,d bytes)".format(
				this.address.toShortString, endTime - startTime, this.keys.size, items(2).bytes.length
			))
		}
	}

	override def toString: String = {
		"Address: %s; Code: %s; Storage: %s".format(this.address.toHexString, this.code.toHexString, storageContent.toString())
	}

}

object ContractDetailsImpl {

	private val logger = LoggerFactory.getLogger("database")

	def decode(bytes: ImmutableBytes): ContractDetailsImpl = {
		val result = new ContractDetailsImpl
		result.decode(bytes)
		result
	}

	def newInstance(address: ImmutableBytes, trie: SecureTrie, code: ImmutableBytes): ContractDetailsImpl = {
		val result = new ContractDetailsImpl
		result.address = address
		result.storageTrieRef.set(trie)
		result.code = code
		result
	}

}