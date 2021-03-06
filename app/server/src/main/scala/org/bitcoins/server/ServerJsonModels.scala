package org.bitcoins.server

import org.bitcoins.commons.jsonmodels.wallet.CoinSelectionAlgo
import org.bitcoins.core.currency.{Bitcoins, Satoshis}
import org.bitcoins.core.protocol.BlockStamp.BlockHeight
import org.bitcoins.core.protocol.transaction.{Transaction, TransactionOutPoint}
import org.bitcoins.core.protocol.{BitcoinAddress, BlockStamp}
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import ujson._
import upickle.default._

import scala.util.{Failure, Try}

// TODO ID?
case class ServerCommand(method: String, params: ujson.Arr)

object ServerCommand {
  implicit val rw: ReadWriter[ServerCommand] = macroRW
}

case class GetBalance(isSats: Boolean)

object GetBalance extends ServerJsonModels {

  def fromJsArr(jsArr: ujson.Arr): Try[GetBalance] = {
    require(jsArr.arr.size == 1,
            s"Bad number of arguments: ${jsArr.arr.size}. Expected: 1")

    Try(GetBalance(jsArr.arr.head.bool))
  }
}

case class GetConfirmedBalance(isSats: Boolean)

object GetConfirmedBalance extends ServerJsonModels {

  def fromJsArr(jsArr: ujson.Arr): Try[GetConfirmedBalance] = {
    require(jsArr.arr.size == 1,
            s"Bad number of arguments: ${jsArr.arr.size}. Expected: 1")

    Try(GetConfirmedBalance(jsArr.arr.head.bool))
  }
}

case class GetUnconfirmedBalance(isSats: Boolean)

object GetUnconfirmedBalance extends ServerJsonModels {

  def fromJsArr(jsArr: ujson.Arr): Try[GetUnconfirmedBalance] = {
    require(jsArr.arr.size == 1,
            s"Bad number of arguments: ${jsArr.arr.size}. Expected: 1")

    Try(GetUnconfirmedBalance(jsArr.arr.head.bool))
  }
}

case class GetAddressInfo(address: BitcoinAddress)

object GetAddressInfo extends ServerJsonModels {

  def fromJsArr(jsArr: ujson.Arr): Try[GetAddressInfo] = {
    require(jsArr.arr.size == 1,
            s"Bad number of arguments: ${jsArr.arr.size}. Expected: 1")

    val address = jsToBitcoinAddress(jsArr.arr.head)

    Try(GetAddressInfo(address))
  }
}

case class SendRawTransaction(tx: Transaction)

object SendRawTransaction extends ServerJsonModels {

  def fromJsArr(jsArr: ujson.Arr): Try[SendRawTransaction] = {
    require(jsArr.arr.size == 1,
            s"Bad number of arguments: ${jsArr.arr.size}. Expected: 1")

    Try(SendRawTransaction(jsToTx(jsArr.arr.head)))
  }
}

case class CombinePSBTs(psbts: Seq[PSBT])

object CombinePSBTs extends ServerJsonModels {

  def fromJsArr(jsArr: ujson.Arr): Try[CombinePSBTs] = {
    require(jsArr.arr.size == 1,
            s"Bad number of arguments: ${jsArr.arr.size}. Expected: 1")

    Try(CombinePSBTs(jsToPSBTSeq(jsArr.arr.head)))
  }
}

case class JoinPSBTs(psbts: Seq[PSBT])

object JoinPSBTs extends ServerJsonModels {

  def fromJsArr(jsArr: ujson.Arr): Try[JoinPSBTs] = {
    CombinePSBTs
      .fromJsArr(jsArr)
      .map(combine => JoinPSBTs(combine.psbts))
  }
}

case class FinalizePSBT(psbt: PSBT)

object FinalizePSBT extends ServerJsonModels {

  def fromJsArr(jsArr: ujson.Arr): Try[FinalizePSBT] = {
    require(jsArr.arr.size == 1,
            s"Bad number of arguments: ${jsArr.arr.size}. Expected: 1")

    Try(FinalizePSBT(jsToPSBT(jsArr.arr.head)))
  }
}

case class ExtractFromPSBT(psbt: PSBT)

object ExtractFromPSBT extends ServerJsonModels {

  def fromJsArr(jsArr: ujson.Arr): Try[ExtractFromPSBT] = {
    require(jsArr.arr.size == 1,
            s"Bad number of arguments: ${jsArr.arr.size}. Expected: 1")

    Try(ExtractFromPSBT(jsToPSBT(jsArr.arr.head)))
  }
}

case class ConvertToPSBT(tx: Transaction)

object ConvertToPSBT extends ServerJsonModels {

  def fromJsArr(jsArr: ujson.Arr): Try[ConvertToPSBT] = {
    require(jsArr.arr.size == 1,
            s"Bad number of arguments: ${jsArr.arr.size}. Expected: 1")

    Try(ConvertToPSBT(jsToTx(jsArr.arr.head)))
  }
}

case class DecodeRawTransaction(tx: Transaction)

object DecodeRawTransaction extends ServerJsonModels {

  def fromJsArr(jsArr: ujson.Arr): Try[DecodeRawTransaction] = {
    jsArr.arr.toList match {
      case tx :: Nil =>
        Try {
          DecodeRawTransaction(Transaction.fromHex(tx.str))
        }
      case other =>
        Failure(
          new IllegalArgumentException(
            s"Bad number of arguments: ${other.length}. Expected: 1"))
    }
  }
}

case class Rescan(
    batchSize: Option[Int],
    startBlock: Option[BlockStamp],
    endBlock: Option[BlockStamp],
    force: Boolean,
    ignoreCreationTime: Boolean)

object Rescan extends ServerJsonModels {

  def fromJsArr(jsArr: ujson.Arr): Try[Rescan] = {

    def parseBlockStamp(value: Value): Option[BlockStamp] =
      nullToOpt(value).map {
        case Str(value) => BlockStamp.fromString(value)
        case Num(value) =>
          val int = value.toInt
          if (int >= 0 && int <= Int.MaxValue)
            BlockHeight(int)
          else throw Value.InvalidData(value, "Expected a positive integer")
        case _: Value =>
          throw Value.InvalidData(value, "Expected a Num or a Str")
      }

    def parseInt(value: Value): Option[Int] =
      nullToOpt(value).map {
        case Str(value) => value.toInt
        case Num(value) => value.toInt
        case _: Value =>
          throw Value.InvalidData(value, "Expected a Num or a Str")
      }

    def parseBoolean(value: Value): Boolean =
      value match {
        case Bool(bool) => bool
        case _: Value   => throw Value.InvalidData(value, "Expected a Bool")
      }

    jsArr.arr.toList match {
      case batchSizeJs :: startJs :: endJs :: forceJs :: ignoreCreationTimeJs :: Nil =>
        Try {
          val batchSize = parseInt(batchSizeJs)
          val start = parseBlockStamp(startJs)
          val end = parseBlockStamp(endJs)
          val force = parseBoolean(forceJs)
          val ignoreCreationTime = parseBoolean(ignoreCreationTimeJs)
          Rescan(batchSize = batchSize,
                 startBlock = start,
                 endBlock = end,
                 force = force,
                 ignoreCreationTime = ignoreCreationTime)
        }
      case Nil =>
        Failure(new IllegalArgumentException("Missing addresses"))

      case other =>
        Failure(
          new IllegalArgumentException(
            s"Bad number of arguments: ${other.length}. Expected: 4"))
    }
  }

}

case class SendToAddress(
    address: BitcoinAddress,
    amount: Bitcoins,
    satoshisPerVirtualByte: Option[SatoshisPerVirtualByte])

object SendToAddress extends ServerJsonModels {

  /// TODO do this in a more coherent fashion
  // custom akka-http directive?
  def fromJsArr(jsArr: ujson.Arr): Try[SendToAddress] = {
    jsArr.arr.toList match {
      case addrJs :: bitcoinsJs :: satsPerVBytesJs :: Nil =>
        Try {
          val address = jsToBitcoinAddress(addrJs)
          val bitcoins = Bitcoins(bitcoinsJs.num)
          val satoshisPerVirtualByte =
            nullToOpt(satsPerVBytesJs).map(satsPerVBytes =>
              SatoshisPerVirtualByte(Satoshis(satsPerVBytes.num.toLong)))
          SendToAddress(address, bitcoins, satoshisPerVirtualByte)
        }
      case Nil =>
        Failure(
          new IllegalArgumentException(
            "Missing address, amount, and fee rate arguments"))

      case other =>
        Failure(
          new IllegalArgumentException(
            s"Bad number of arguments: ${other.length}. Expected: 3"))
    }
  }

}

case class SendFromOutpoints(
    outPoints: Vector[TransactionOutPoint],
    address: BitcoinAddress,
    amount: Bitcoins,
    satoshisPerVirtualByte: Option[SatoshisPerVirtualByte])

object SendFromOutpoints extends ServerJsonModels {

  def fromJsArr(jsArr: ujson.Arr): Try[SendFromOutpoints] = {
    jsArr.arr.toList match {
      case outPointsJs :: addrJs :: bitcoinsJs :: satsPerVBytesJs :: Nil =>
        Try {
          val outPoints = jsToTransactionOutPointSeq(outPointsJs).toVector
          val address = jsToBitcoinAddress(addrJs)
          val bitcoins = Bitcoins(bitcoinsJs.num)
          val satoshisPerVirtualByte =
            nullToOpt(satsPerVBytesJs).map(satsPerVBytes =>
              SatoshisPerVirtualByte(Satoshis(satsPerVBytes.num.toLong)))
          SendFromOutpoints(outPoints,
                            address,
                            bitcoins,
                            satoshisPerVirtualByte)
        }
      case Nil =>
        Failure(
          new IllegalArgumentException(
            "Missing outPoints, address, amount, and fee rate arguments"))

      case other =>
        Failure(
          new IllegalArgumentException(
            s"Bad number of arguments: ${other.length}. Expected: 4"))
    }
  }

}

case class SendWithAlgo(
    address: BitcoinAddress,
    amount: Bitcoins,
    satoshisPerVirtualByte: Option[SatoshisPerVirtualByte],
    algo: CoinSelectionAlgo)

object SendWithAlgo extends ServerJsonModels {

  def fromJsArr(jsArr: ujson.Arr): Try[SendWithAlgo] = {
    jsArr.arr.toList match {
      case addrJs :: bitcoinsJs :: satsPerVBytesJs :: algoJs :: Nil =>
        Try {
          val address = jsToBitcoinAddress(addrJs)
          val bitcoins = Bitcoins(bitcoinsJs.num)
          val satoshisPerVirtualByte =
            nullToOpt(satsPerVBytesJs).map(satsPerVBytes =>
              SatoshisPerVirtualByte(Satoshis(satsPerVBytes.num.toLong)))
          val algo = jsToCoinSelectionAlgo(algoJs)

          SendWithAlgo(address, bitcoins, satoshisPerVirtualByte, algo)
        }
      case Nil =>
        Failure(
          new IllegalArgumentException(
            "Missing address, amount, fee rate, and algo arguments"))

      case other =>
        Failure(
          new IllegalArgumentException(
            s"Bad number of arguments: ${other.length}. Expected: 4"))
    }
  }

}

case class OpReturnCommit(
    message: String,
    hashMessage: Boolean,
    feeRateOpt: Option[SatoshisPerVirtualByte])

object OpReturnCommit extends ServerJsonModels {

  def fromJsArr(jsArr: ujson.Arr): Try[OpReturnCommit] = {
    jsArr.arr.toList match {
      case messageJs :: hashMessageJs :: feeRateOptJs :: Nil =>
        Try {
          val message = messageJs.str
          val hashMessage = hashMessageJs.bool
          val feeRateOpt =
            nullToOpt(feeRateOptJs).map(satsPerVBytes =>
              SatoshisPerVirtualByte(Satoshis(satsPerVBytes.num.toLong)))
          OpReturnCommit(message, hashMessage, feeRateOpt)
        }
      case Nil =>
        Failure(
          new IllegalArgumentException(
            "Missing message, hashMessage, and fee rate arguments"))

      case other =>
        Failure(
          new IllegalArgumentException(
            s"Bad number of arguments: ${other.length}. Expected: 3"))
    }
  }

}

trait ServerJsonModels {

  def jsToBitcoinAddress(js: Value): BitcoinAddress = {
    try {
      BitcoinAddress.fromStringExn(js.str)
    } catch {
      case _: IllegalArgumentException =>
        throw Value.InvalidData(js, "Expected a valid address")
    }
  }

  def jsToPSBTSeq(js: Value): Seq[PSBT] = {
    js.arr.foldLeft(Seq.empty[PSBT])((seq, psbt) => seq :+ jsToPSBT(psbt))
  }

  def jsToPSBT(js: Value): PSBT = PSBT.fromString(js.str)

  def jsToTransactionOutPointSeq(js: Value): Seq[TransactionOutPoint] = {
    js.arr.foldLeft(Seq.empty[TransactionOutPoint])((seq, outPoint) =>
      seq :+ jsToTransactionOutPoint(outPoint))
  }

  def jsToTransactionOutPoint(js: Value): TransactionOutPoint =
    TransactionOutPoint(js.str)

  def jsToCoinSelectionAlgo(js: Value): CoinSelectionAlgo =
    CoinSelectionAlgo
      .fromString(js.str)
      .getOrElse(
        throw new IllegalArgumentException("Invalid CoinSelectionAlgo"))

  def jsToTx(js: Value): Transaction = Transaction.fromHex(js.str)

  def nullToOpt(value: Value): Option[Value] =
    value match {
      case Null                      => None
      case Arr(arr) if arr.isEmpty   => None
      case Arr(arr) if arr.size == 1 => Some(arr.head)
      case _: Value                  => Some(value)
    }
}
