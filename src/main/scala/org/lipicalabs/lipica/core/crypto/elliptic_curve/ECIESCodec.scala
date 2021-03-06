package org.lipicalabs.lipica.core.crypto.elliptic_curve

import java.io.{ByteArrayOutputStream, ByteArrayInputStream}
import java.math.BigInteger
import java.security.SecureRandom

import org.spongycastle.crypto.agreement.kdf.ConcatenationKDFGenerator
import org.spongycastle.crypto.generators.ECKeyPairGenerator
import org.spongycastle.crypto.modes.SICBlockCipher
import org.spongycastle.crypto.BufferedBlockCipher
import org.spongycastle.crypto.agreement.ECDHBasicAgreement
import org.spongycastle.crypto.digests.SHA256Digest
import org.spongycastle.crypto.engines.AESFastEngine
import org.spongycastle.crypto.macs.HMac
import org.spongycastle.crypto.params._
import org.spongycastle.math.ec.ECPoint

/**
 * 楕円曲線暗号による暗号化＆復号処理の実行者オブジェクトです。
 *
 * Created by IntelliJ IDEA.
 * 2016/01/27 20:13
 * YANAGISAWA, Kentaro
 */
object ECIESCodec {

	private val KeySize: Int = 128
	private val Curve = ECKeyLike.CURVE

	/**
	 * 暗号化を実行し、暗号化されたデータを返します。
	 *
	 * @param publicKeyPoint 利用する公開鍵。
	 * @param plainData 暗号化対象の平文。
	 */
	def encrypt(publicKeyPoint: ECPoint, plainData: Array[Byte]): Array[Byte] = {
		val eGen = new ECKeyPairGenerator
		val random = new SecureRandom
		val gParam = new ECKeyGenerationParameters(Curve, random)
		eGen.init(gParam)
		
		val IV = new Array[Byte](KeySize / 8)
		new SecureRandom().nextBytes(IV)

		val ephemeralKeyPair = eGen.generateKeyPair
		val prv = ephemeralKeyPair.getPrivate.asInstanceOf[ECPrivateKeyParameters].getD
		val pub = ephemeralKeyPair.getPublic.asInstanceOf[ECPublicKeyParameters].getQ
		val iesEngine: IESEngine = makeIESEngine(isEncrypt = true, publicKeyPoint, prv, IV)

		val keygenParams = new ECKeyGenerationParameters(Curve, random)
		val generator = new ECKeyPairGenerator
		generator.init(keygenParams)

		val gen = new ECKeyPairGenerator
		gen.init(new ECKeyGenerationParameters(Curve, random))
		try {
			val cipher = iesEngine.processBlock(plainData, 0, plainData.length)
			val bos = new ByteArrayOutputStream
			bos.write(pub.getEncoded(false))
			bos.write(IV)
			bos.write(cipher)
			bos.toByteArray
		} catch {
			case e: Throwable => throw e
		}
	}

	/**
	 * 暗号化されたデータを復号し、平文を返します。
	 *
	 * @param privateKey 復号に利用する秘密鍵。
	 * @param encryptedData 暗号化されたデータ。
	 */
	def decrypt(privateKey: BigInt, encryptedData: Array[Byte]): Array[Byte] = {
		val is = new ByteArrayInputStream(encryptedData)
		val ephemBytes = new Array[Byte](2 * ((Curve.getCurve.getFieldSize + 7) / 8) + 1)
		is.read(ephemBytes)
		val ephem = Curve.getCurve.decodePoint(ephemBytes)

		val IV = new Array[Byte](KeySize / 8)
		is.read(IV)

		val cipherBody = new Array[Byte](is.available)
		is.read(cipherBody)
		decrypt(ephem, privateKey, IV, cipherBody)
	}

	/**
	 * 空間面におけるオーバーヘッドのバイト数を返します。
	 */
	def getOverhead: Int = {
		65 + KeySize / 8 + 32
	}

	private def makeIESEngine(isEncrypt: Boolean, publicKey: ECPoint, privateKey: BigInteger, IV: Array[Byte]): IESEngine = {
		val d = Array[Byte]()
		val e = Array[Byte]()
		val p = new IESWithCipherParameters(d, e, KeySize, KeySize)
		val parametersWithIV = new ParametersWithIV(p, IV)

		val aesFastEngine = new AESFastEngine
		val iesEngine = new IESEngine(new ECDHBasicAgreement, new ConcatenationKDFGenerator(new SHA256Digest), new HMac(new SHA256Digest), new SHA256Digest, new BufferedBlockCipher(new SICBlockCipher(aesFastEngine)))
		iesEngine.init(isEncrypt, new ECPrivateKeyParameters(privateKey, Curve), new ECPublicKeyParameters(publicKey, Curve), parametersWithIV)
		iesEngine
	}

	private def decrypt(ephemeralKey: ECPoint, privateKey: BigInt, IV: Array[Byte], encryptedData: Array[Byte]): Array[Byte] = {
		val d = Array[Byte]()
		val e = Array[Byte]()
		val p = new IESWithCipherParameters(d, e, KeySize, KeySize)
		val parametersWithIV = new ParametersWithIV(p, IV)

		val aesFastEngine = new AESFastEngine
		val iesEngine = new IESEngine(new ECDHBasicAgreement, new ConcatenationKDFGenerator(new SHA256Digest), new HMac(new SHA256Digest), new SHA256Digest, new BufferedBlockCipher(new SICBlockCipher(aesFastEngine)))
		iesEngine.init(false, new ECPrivateKeyParameters(privateKey.bigInteger, Curve), new ECPublicKeyParameters(ephemeralKey, Curve), parametersWithIV)
		iesEngine.processBlock(encryptedData, 0, encryptedData.length)
	}

}
