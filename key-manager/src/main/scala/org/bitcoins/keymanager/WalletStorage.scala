package org.bitcoins.keymanager

import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.NoSuchElementException

import org.bitcoins.core.compat._
import org.bitcoins.crypto.{AesEncryptedData, AesIV, AesPassword, AesSalt}
import org.slf4j.LoggerFactory
import scodec.bits.ByteVector

import scala.util.{Failure, Success, Try}

// what do we do if seed exists? error if they aren't equal?
object WalletStorage {

  val ENCRYPTED_SEED_FILE_NAME: String =
    "encrypted-bitcoin-s-seed.json"

  import org.bitcoins.core.compat.JavaConverters._

  /** Start of bitcoin-s wallet project, Block 555,990 block time on 2018-12-28 */
  val FIRST_BITCOIN_S_WALLET_TIME = 1546042867L

  private val logger = LoggerFactory.getLogger(getClass)

  /** Checks if a wallet seed exists in datadir */
  def seedExists(seedPath: java.nio.file.Path): Boolean = {
    Files.exists(seedPath)
  }

  private object MnemonicJsonKeys {
    val IV = "iv"
    val CIPHER_TEXT = "cipherText"
    val SALT = "salt"
    val CREATION_TIME = "creationTime"
  }

  /**
    * Writes the encrypted mnemonic to disk.
    * If we encounter a file in the place we're about
    * to write to, we move it to a backup location
    * with the current epoch timestamp as part of
    * the file name.
    */
  def writeMnemonicToDisk(seedPath: Path, mnemonic: EncryptedMnemonic): Path = {
    val encrypted = mnemonic.value
    val jsObject = {
      import MnemonicJsonKeys._
      ujson.Obj(
        IV -> encrypted.iv.hex,
        CIPHER_TEXT -> encrypted.cipherText.toHex,
        SALT -> mnemonic.salt.bytes.toHex,
        CREATION_TIME -> ujson.Num(
          mnemonic.creationTime.getEpochSecond.toDouble)
      )
    }

    logger.info(s"Writing mnemonic to $seedPath")

    val writtenJs = ujson.write(jsObject)

    def writeJsToDisk() = {
      val writtenPath = Files.write(seedPath, writtenJs.getBytes())
      logger.trace(s"Wrote encrypted mnemonic to $seedPath")

      writtenPath
    }

    //check to see if a mnemonic exists already...
    val foundMnemonicOpt: Option[EncryptedMnemonic] =
      readEncryptedMnemonicFromDisk(seedPath) match {
        case CompatLeft(_) =>
          None
        case CompatRight(mnemonic) => Some(mnemonic)
      }

    foundMnemonicOpt match {
      case None =>
        logger.trace(s"$seedPath does not exist")
        writeJsToDisk()
      case Some(_) =>
        logger.info(s"$seedPath already exists")
        throw new RuntimeException(
          s"Attempting to overwrite an existing mnemonic seed, this is dangerous!")
    }
  }

  /** Reads the raw encrypted mnemonic from disk,
    * performing no decryption
    */
  private def readEncryptedMnemonicFromDisk(
      seedPath: Path): CompatEither[ReadMnemonicError, EncryptedMnemonic] = {

    val jsonE: CompatEither[ReadMnemonicError, ujson.Value] = {
      if (Files.isRegularFile(seedPath)) {
        val rawJson = Files.readAllLines(seedPath).asScala.mkString("\n")
        logger.debug(s"Read raw encrypted mnemonic from $seedPath")

        Try {
          ujson.read(rawJson)
        } match {
          case Failure(ujson.ParseException(clue, _, _, _)) =>
            CompatLeft(ReadMnemonicError.JsonParsingError(clue))
          case Failure(exception) => throw exception

          case Success(value) =>
            logger.debug(s"Parsed $seedPath into valid json")
            CompatRight(value)
        }
      } else {
        logger.error(s"Encrypted mnemonic not found at $seedPath")
        CompatLeft(ReadMnemonicError.NotFoundError)
      }
    }

    import MnemonicJsonKeys._
    import ReadMnemonicError._

    val readJsonTupleEither: CompatEither[
      ReadMnemonicError,
      (String, String, String, Long)] = jsonE.flatMap { json =>
      logger.trace(s"Read encrypted mnemonic JSON: $json")
      val creationTimeNum = Try(json(CREATION_TIME).num.toLong) match {
        case Success(value) =>
          value
        case Failure(err) if err.isInstanceOf[NoSuchElementException] =>
          // If no CREATION_TIME is set, we set date to start of bitcoin-s wallet project
          // default is Block 555,990 block time on 2018-12-28
          FIRST_BITCOIN_S_WALLET_TIME
        case Failure(exception) => throw exception
      }
      Try {
        val ivString = json(IV).str
        val cipherTextString = json(CIPHER_TEXT).str
        val rawSaltString = json(SALT).str
        (ivString, cipherTextString, rawSaltString, creationTimeNum)
      } match {
        case Success(value)     => CompatRight(value)
        case Failure(exception) => throw exception
      }
    }

    val encryptedEither: CompatEither[ReadMnemonicError, EncryptedMnemonic] =
      readJsonTupleEither.flatMap {
        case (rawIv, rawCipherText, rawSalt, rawCreationTime) =>
          val encryptedOpt = for {
            iv <- ByteVector.fromHex(rawIv).map(AesIV.fromValidBytes)
            cipherText <- ByteVector.fromHex(rawCipherText)
            salt <- ByteVector.fromHex(rawSalt).map(AesSalt(_))
          } yield {
            logger.debug(
              s"Parsed contents of $seedPath into an EncryptedMnemonic")
            EncryptedMnemonic(AesEncryptedData(cipherText, iv),
                              salt,
                              Instant.ofEpochSecond(rawCreationTime))
          }
          val toRight: Option[
            CompatRight[ReadMnemonicError, EncryptedMnemonic]] = encryptedOpt
            .map(CompatRight(_))

          toRight.getOrElse(
            CompatLeft(JsonParsingError("JSON contents was not hex strings")))
      }
    encryptedEither
  }

  /**
    * Reads the wallet mnemonic from disk and tries to parse and
    * decrypt it
    */
  def decryptMnemonicFromDisk(
      seedPath: Path,
      passphrase: AesPassword): Either[ReadMnemonicError, DecryptedMnemonic] = {

    val encryptedEither = readEncryptedMnemonicFromDisk(seedPath)

    val decryptedEither: CompatEither[ReadMnemonicError, DecryptedMnemonic] =
      encryptedEither.flatMap { encrypted =>
        encrypted.toMnemonic(passphrase) match {
          case Failure(exc) =>
            logger.error(s"Error when decrypting $encrypted: $exc")
            CompatLeft(ReadMnemonicError.DecryptionError)
          case Success(mnemonic) =>
            logger.debug(s"Decrypted $encrypted successfully")
            val decryptedMnemonic =
              DecryptedMnemonic(mnemonic, encrypted.creationTime)
            CompatRight(decryptedMnemonic)
        }
      }

    decryptedEither match {
      case CompatLeft(value)  => Left(value)
      case CompatRight(value) => Right(value)
    }
  }
}

sealed trait ReadMnemonicError { self: Error => }

object ReadMnemonicError {

  /**
    * Something went wrong while decrypting the mnemonic.
    * Most likely the passphrase was bad
    */
  case object DecryptionError
      extends Error(s"Could not decrypt mnemonic!")
      with ReadMnemonicError

  /**
    * Something went wrong while parsing the encrypted
    * mnemonic into valid JSON
    */
  case class JsonParsingError(message: String)
      extends Error(s"Error when parsing JSON: $message")
      with ReadMnemonicError

  /** The encrypted mnemonic was not found on disk */
  case object NotFoundError
      extends Error("Could not find mnemonic!")
      with ReadMnemonicError
}
