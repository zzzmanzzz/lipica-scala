package org.lipicalabs.lipica.core.trie

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import org.lipicalabs.lipica.core.utils._
import org.lipicalabs.lipica.core.crypto.digest.{Digest256, DigestValue, DigestUtils}
import org.lipicalabs.lipica.core.datastore.datasource.KeyValueDataSource
import org.slf4j.LoggerFactory
import org.lipicalabs.lipica.core.bytes_codec.RBACCodec

import scala.annotation.tailrec

/**
 * Merkle Patricia Treeの実装クラスです。
 *
 * @author YANAGISAWA, Kentaro
 * @since 2015/09/30
 */
class TrieImpl private[trie](_db: KeyValueDataSource, _root: DigestValue) extends Trie {

	private[trie] def this(_db: KeyValueDataSource) = this(_db, DigestUtils.EmptyTrieHash)

	import TrieImpl._
	import org.lipicalabs.lipica.core.bytes_codec.NibbleCodec._

	/**
	 * 木構造のルート要素。
	 */
	private val rootRef = new AtomicReference[TrieNode](TrieNode.fromDigest(_root))
	override def root_=(node: TrieNode): TrieImpl = {
		if (node.isEmpty) {
			this.rootRef.set(TrieNode.emptyTrieNode)
		} else {
			this.rootRef.set(node)
		}
		this
	}
	def root_=(value: DigestValue): TrieImpl = {
		this.root = TrieNode.fromDigest(value)
		this
	}
	def root: TrieNode = this.rootRef.get

	/**
	 * 最上位レベルのハッシュ値を計算して返します。
	 */
	override def rootHash: DigestValue = this.rootRef.get.hash

	/**
	 * 前回のルート要素。（undo に利用する。）
	 */
	private val prevRootRef = new AtomicReference[TrieNode](this.rootRef.get)
	def prevRoot: TrieNode = this.prevRootRef.get

	/**
	 * 永続化機構のラッパー。
	 */
	private val backendRef = new AtomicReference[TrieBackend](new TrieBackend(_db))
	def backend: TrieBackend = this.backendRef.get
	def backend_=(v: TrieBackend): Unit = this.backendRef.set(v)

	/**
	 * key 文字列に対応する値を取得して返します。
	 */
	def get(key: String): ImmutableBytes = get(ImmutableBytes(key.getBytes(StandardCharsets.UTF_8)))

	/**
	 * key に対応する値を取得して返します。
	 */
	override def get(key: ImmutableBytes): ImmutableBytes = {
		if (logger.isDebugEnabled) {
			logger.debug("<TrieImpl> Retrieving key [%s]".format(key.toHexString))
		}
		//終端記号がついた、１ニブル１バイトのバイト列に変換する。
		val convertedKey = binToNibbles(key)
		//ルートノード以下の全体を探索する。
		get(this.root, convertedKey)
	}

	@tailrec
	private def get(aNode: TrieNode, key: ImmutableBytes): ImmutableBytes = {
		if (key.isEmpty || aNode.isEmpty) {
			//キーが消費し尽くされているか、ノードに子孫がいない場合、そのノードの値を返す。
			return aNode.nodeValue
		}
		retrieveNodeFromBackend(aNode) match {
			case currentNode: ShortcutNode =>
				//２要素のショートカットノード。
				//このノードのキーを長ったらしい表現に戻す。
				val nodeKey = unpackToNibbles(currentNode.shortcutKey)
				//値を読み取る。
				if ((nodeKey.length <= key.length) && key.copyOfRange(0, nodeKey.length) == nodeKey) {
					//このノードのキーが、指定されたキーの接頭辞である。
					//子孫を再帰的に探索する。
					get(currentNode.childNode, key.copyOfRange(nodeKey.length, key.length))
				} else {
					//このノードは、指定されたキーの接頭辞ではない。
					//つまり、要求されたキーに対応する値は存在しない。
					ImmutableBytes.empty
				}
			case currentNode: RegularNode =>
				//このノードは、17要素の通常ノードである。
				//子孫をたどり、キーを１ニブル消費して探索を継続する。
				val child = currentNode.child(key(0))
				get(child, key.copyOfRange(1, key.length))
			case _ =>
				ImmutableBytes.empty
		}
	}

	/**
	 * key文字列 対応するエントリを削除します。
	 */
	def delete(key: String): Unit = {
		delete(ImmutableBytes(key.getBytes(StandardCharsets.UTF_8)))
	}

	/**
	 * 指定されたkeyに対応するエントリを削除します。
	 */
	override def delete(key: ImmutableBytes): Unit = {
		update(key, ImmutableBytes.empty)
	}

	/**
	 * key文字列 に対して値文字列を関連付けます。
	 */
	def update(key: String, value: String): Unit = {
		update(ImmutableBytes(key.getBytes(StandardCharsets.UTF_8)), ImmutableBytes(value.getBytes(StandardCharsets.UTF_8)))
	}

	/**
	 * key に対して値を関連付けます。
	 */
	override def update(key: ImmutableBytes, value: ImmutableBytes): Unit = {
		if (logger.isDebugEnabled) {
			logger.debug("<TrieImpl> Updating [%s] -> [%s]".format(key.toHexString, value.toHexString))
			logger.debug("<TrieImpl> Old root-hash: %s".format(rootHash.toHexString))
		}
		//終端記号がついた、１ニブル１バイトのバイト列に変換する。
		val nibbleKey = binToNibbles(key)
		val result = insertOrDelete(this.root, nibbleKey, value)
		//ルート要素を更新する。
		this.root = result
		if (logger.isDebugEnabled) {
			logger.debug("<TrieImpl> Updated [%s] -> [%s]".format(key.toHexString, value.toHexString))
			logger.debug("<TrieImpl> New root-hash: %s".format(rootHash.toHexString))
		}
	}

	private def insertOrDelete(node: TrieNode, key: ImmutableBytes, value: ImmutableBytes): TrieNode = {
		if (value.nonEmpty) {
			insert(node, key, ValueNode(value))
		} else {
			delete(node, key)
		}
	}

	/**
	 * キーに対応する値を登録します。
	 */
	private def insert(aNode: TrieNode, key: ImmutableBytes, valueNode: TrieNode): TrieNode = {
		if (key.isEmpty) {
			//終端記号すらない空バイト列ということは、
			//再帰的な呼び出しによってキーが消費しつくされたということ。
			//これ以上は処理する必要がない。
			return valueNode
		}
		val node = retrieveNodeFromBackend(aNode)
		if (node.isEmpty) {
			//親ノードが指定されていないので、新たな２要素ノードを作成して返す。
			val newNode = TrieNode(packNibbles(key), valueNode)
			return putNodeToBackend(newNode)
		}
		node match {
			case currentNode: ShortcutNode =>
				//２要素のショートカットノードである。
				//キーを長ったらしい表現に戻す。
				val nodeKey = unpackToNibbles(currentNode.shortcutKey)
				//キーの共通部分の長さをカウントする。
				val matchingLength = ByteUtils.matchingLength(key, nodeKey)
				val createdNode =
					if (matchingLength == nodeKey.length) {
						//既存ノードのキー全体が、新たなキーの接頭辞になっている。
						val remainingKeyPart = key.copyOfRange(matchingLength, key.length)
						//子孫を作る。
						insert(currentNode.childNode, remainingKeyPart, valueNode)
					} else {
						//既存ノードのキーの途中で分岐がある。
						//2要素のショートカットノードを、17要素の通常ノードに変換する。
						//従来の要素。
						val oldNode = insert(EmptyNode, nodeKey.copyOfRange(matchingLength + 1, nodeKey.length), currentNode.childNode)
						//追加された要素。
						val newNode = insert(EmptyNode, key.copyOfRange(matchingLength + 1, key.length), valueNode)
						//異なる最初のニブルに対応するノードを記録して、分岐させる。
						val scaledSlice = createRegularNodeSlice
						scaledSlice(nodeKey(matchingLength)) = oldNode
						scaledSlice(key(matchingLength)) = newNode
						putNodeToBackend(TrieNode(scaledSlice.toSeq))
					}
				if (matchingLength == 0) {
					//既存ノードのキーと新たなキーとの間に共通点はないので、
					//いま作成された通常ノードが、このノードの代替となる。
					createdNode
				} else {
					//このノードと今作られたノードとをつなぐノードを作成する。
					val bridgeNode = TrieNode(packNibbles(key.copyOfRange(0, matchingLength)), createdNode)
					putNodeToBackend(bridgeNode)
				}
			case currentNode: RegularNode =>
				//もともと17要素の通常ノードである。
				val newNode = copyRegularNode(currentNode)
				//普通にノードを更新して、保存する。
				newNode(key(0)) = insert(currentNode.child(key(0)), key.copyOfRange(1, key.length), valueNode)
				putNodeToBackend(TrieNode(newNode.toSeq))
			case other =>
				val s = if (other eq null) "null" else other.getClass.getSimpleName
				ErrorLogger.logger.warn("<Trie> Trie error: Node is %s".format(s))
				logger.warn("<Trie> Trie error: Node is %s".format(s))
				valueNode
		}
	}

	/**
	 * キーに対応するエントリーを削除します。
	 */
	private def delete(node: TrieNode, key: ImmutableBytes): TrieNode = {
		if (key.isEmpty || node.isEmpty) {
			//何もしない。
			return EmptyNode
		}
		retrieveNodeFromBackend(node) match {
			case currentNode: ShortcutNode =>
				//２要素のショートカットノードである。
				//長ったらしい表現に戻す。
				val packedKey = currentNode.shortcutKey
				val nodeKey = unpackToNibbles(packedKey)

				if (nodeKey == key) {
					//ぴたり一致。 これが削除対象である。
					EmptyNode
				} else if (nodeKey == key.copyOfRange(0, nodeKey.length)) {
					//このノードのキーが、削除すべきキーの接頭辞である。
					//再帰的に削除を試行する。削除した結果、新たにこのノードの直接の子になるべきノードが返ってくる。
					val deleteResult = delete(currentNode.childNode, key.copyOfRange(nodeKey.length, key.length))
					val newNode = retrieveNodeFromBackend(deleteResult) match {
							case newChild: ShortcutNode =>
								//削除で発生する跳躍をつなぐ。
								//この操作こそが、削除そのものである。
								val newKey = nodeKey ++ unpackToNibbles(newChild.shortcutKey)
								TrieNode(packNibbles(newKey), newChild.childNode)
							case _ =>
								TrieNode(packedKey, deleteResult)
						}
					putNodeToBackend(newNode)
				} else {
					//このノードは関係ない。
					node
				}
			case currentNode: RegularNode =>
				//もともと17要素の通常ノードである。
				val items = copyRegularNode(currentNode)
				//再帰的に削除する。
				val newChild = delete(items(key(0)), key.copyOfRange(1, key.length))
				//新たな子供をつなぎ直す。これが削除操作の本体である。
				items(key(0)) = newChild

				val idx = analyzeRegularNode(items)
				val newNode =
					if (idx == TERMINATOR.toInt) {
						//値以外は、すべてのキーが空白である。
						//すなわち、このノードには子はいない。
						//したがって、「終端記号 -> 値」のショートカットノードを生成する。
						TrieNode(packNibbles(ImmutableBytes.fromOneByte(TERMINATOR)), items(idx))
					} else if (0 <= idx) {
						//１ノードだけ子供がいて、このノードには値がない。
						//したがって、このノードと唯一の子供とを、ショートカットノードに変換できる。
							retrieveNodeFromBackend(items(idx)) match {
							case child: ShortcutNode =>
								val concat = ImmutableBytes.fromOneByte(idx.toByte) ++ unpackToNibbles(child.shortcutKey)
								TrieNode(packNibbles(concat), child.childNode)
							case _ =>
								TrieNode(packNibbles(ImmutableBytes.fromOneByte(idx.toByte)), items(idx))
						}
					} else {
						//２ノード以上子供がいるか、子どもと値がある。
						TrieNode(items.toSeq)
					}
				putNodeToBackend(newNode)
			case other =>
				if (logger.isDebugEnabled) {
					//存在しないノードの削除？
					val s = if (other eq null) "null" else other.getClass.getSimpleName
					logger.debug("<Trie> Trie error: Node is %s".format(s))
				}
				EmptyNode
		}
	}

	/**
	 * 17要素の通常ノードを分析して、
	 * (1) 値のみを持ち、子ノードを持たない場合。
	 * (2) １個の子ノードのみを持ち、それ以外に子ノードも値も持たない場合。
	 * (3) 上記のいずれでもない場合。（つまり、複数の子ノードを持つか、子ノードも値も持っている場合。）
	 * を判別して、
	 * (1) もしくは (2) の場合には、0 - 15 もしくは 16（TERMINATOR）の値を返し、
	 * (3) の場合には負の値を返します。
	 */
	private def analyzeRegularNode(node: Array[TrieNode]): Int = {
		var idx = -1
		(0 until TrieNode.RegularSize).foreach {
			i => {
				if (node(i) != EmptyNode) {
					if (idx == -1) {
						idx = i
					} else {
						idx = -2
					}
				}
			}
		}
		idx
	}

	/**
	 * 渡されたノードを、バックエンドに登録します。
	 */
	private def putNodeToBackend(node: TrieNode): TrieNode = {
		this.backend.put(node) match {
			case Left(n) =>
				//値がそのままである。
				n
			case Right(digest) =>
				//長かったので、ハッシュ値が返ってきたということ。
				TrieNode.fromDigest(digest)
		}
	}

	/**
	 * 渡されたノードがダイジェスト値である場合に、
	 * 対応する具体的なノードをバックエンドから取得し、再構築して返します。
	 */
	private def retrieveNodeFromBackend(node: TrieNode): TrieNode = {
		if (!node.isDigestNode) {
			return node
		}
		if (node.isEmpty) {
			EmptyNode
		} else if (node.hash == DigestUtils.EmptyTrieHash) {
			EmptyNode
		} else {
			//対応する値を引いて返す。
			val encodedBytes = this.backend.get(node.hash).encodedBytes
			TrieNode.decode(encodedBytes)
		}
	}

	override def sync(): Unit = {
		this.backend.commit()
		this.prevRootRef.set(root)
	}

	override def undo(): Unit = {
		this.backend.undo()
		this.rootRef.set(prevRoot)
	}

	override def validate: Boolean = Option(this.backend.get(rootHash)).isDefined

	/**
	 * このTrieの現在のグラフに含まれないすべてのデータを、バックエンドから削除します。
	 */
	def executeBackendGC(): Unit = {
		val startTime = System.currentTimeMillis

		val collectAction = new CollectFullSetOfNodes
		this.scanTree(this.rootHash, collectAction)
		val collectedHashes = collectAction.getCollectedHashes

		val toRemoveSet = this.backend.entries.keySet.diff(collectedHashes)
		for (key <- toRemoveSet) {
			this.backend.delete(key.bytes)
			if (logger.isTraceEnabled) {
				logger.trace("<TrieImpl> Garbage collected node: [%s]".format(key.toHexString))
			}
		}
		logger.info("<TrieImpl> GC nodes, size: [%,d]".format(toRemoveSet.size))
		logger.info("<TrieImpl> GC time: [%,d ms]".format(System.currentTimeMillis - startTime))
	}

	def copy: TrieImpl = {
		val another = new TrieImpl(this.backend.dataSource, rootHash)
		this.backend.entries.foreach {
			each => another.backend.privatePut(each._1, each._2)
		}
		another
	}

	private def scanTree(hash: DigestValue, action: ScanAction): Unit = {
		val encodedBytes = this.backend.get(hash).encodedBytes
		if (encodedBytes.isEmpty) {
			return
		}
		TrieNode.decode(encodedBytes) match {
			case shortcut: ShortcutNode =>
				if (shortcut.childNode.isDigestNode) {
					scanTree(shortcut.childNode.hash, action)
				}
				action.doOnNode(hash, shortcut)
			case regular: RegularNode =>
				(0 until TrieNode.RegularSize).foreach {
					i => {
						val child = regular.child(i)
						if (child.isDigestNode) {
							scanTree(child.hash, action)
						}
					}
				}
				action.doOnNode(hash, regular)
			case _ => ()
		}
	}

	/**
	 * このTrieの中身に、渡されたバイト列の中身を充填します。
	 * @param data 符号化されたバイト列。
	 */
	def deserialize(data: ImmutableBytes): Unit = {
		RBACCodec.Decoder.decode(data) match {
			case Right(result) =>
				val keys = result.items.head.bytes
				val valuesSeq = result.items(1).items.map(_.bytes)
				val encodedRoot = result.items(2).bytes

				valuesSeq.indices.foreach {i => {
					val encodedBytes = valuesSeq(i)
					val key = new Array[Byte](32)
					keys.copyTo(i * 32, key, 0, 32)

					this.backend.put(Digest256(key), encodedBytes)
				}}
				this.root = TrieNode.decode(encodedRoot)
			case Left(e) =>
				ErrorLogger.logger.warn("<TrieImpl> Deserialization error.", e)
				logger.warn("<TrieImpl> Deserialization error.", e)
		}
	}

	/**
	 * このTrieの中身をバイト列に符号化して返します。
	 * @return 符号化されたバイト列。
	 */
	def serialize: ImmutableBytes = {
		val nodes = this.backend.entries
		val encodedKeys = nodes.keys.foldLeft(Array.emptyByteArray)((accum, each) => accum ++ each.toByteArray)
		val encodedValues = nodes.values.map(each => each.encodedBytes)
		val encodedRoot = RBACCodec.Encoder.encode(this.root.hash)
		RBACCodec.Encoder.encode(Seq(encodedKeys, encodedValues, encodedRoot))
	}

	/**
	 * このTrieの中身を文字列化して返します。
	 * @return human readable な文字列。
	 */
	override def dumpToString: String = {
		val traceAction = new TraceAllNodes
		this.scanTree(this.rootHash, traceAction)

		val rootString = "root: %s => %s\n".format(rootHash.toHexString, this.root.toString)
		rootString + traceAction.getOutput
	}

	override def equals(o: Any): Boolean = {
		o match {
			case another: Trie => this.rootHash == another.rootHash
			case _ => false
		}
	}
}

object TrieImpl {
	private val logger = LoggerFactory.getLogger("trie")

	def newInstance: TrieImpl = new TrieImpl(null)
	def newInstance(ds: KeyValueDataSource): TrieImpl = new TrieImpl(ds)

	private def createRegularNodeSlice: Array[TrieNode] = {
		(0 until TrieNode.RegularSize).map(_ => EmptyNode).toArray
	}

	/**
	 * １７要素ノードの要素を、可変の配列に変換する。
	 */
	private def copyRegularNode(node: RegularNode): Array[TrieNode] = {
		(0 until TrieNode.RegularSize).map(i => Option(node.child(i)).getOrElse(EmptyNode)).toArray
	}
}
