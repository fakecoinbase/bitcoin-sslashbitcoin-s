package org.bitcoins.server

import akka.actor.ActorSystem
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import org.bitcoins.commons.serializers.Picklers._
import org.bitcoins.core.currency._
import org.bitcoins.node.Node
import org.bitcoins.wallet.api.WalletApi

import scala.concurrent.Future
import scala.util.{Failure, Success}

case class WalletRoutes(wallet: WalletApi, node: Node)(implicit
    system: ActorSystem)
    extends ServerRoute {
  import system.dispatcher

  def handleCommand: PartialFunction[ServerCommand, StandardRoute] = {

    case ServerCommand("isempty", _) =>
      complete {
        wallet.isEmpty().map { empty =>
          Server.httpSuccess(empty)
        }
      }

    case ServerCommand("getbalance", arr) =>
      GetBalance.fromJsArr(arr) match {
        case Failure(exception) =>
          reject(ValidationRejection("failure", Some(exception)))
        case Success(GetBalance(isSats)) =>
          complete {
            wallet.getBalance().map { balance =>
              Server.httpSuccess(
                if (isSats) {
                  balance.satoshis.toString
                } else {
                  Bitcoins(balance.satoshis).toString
                }
              )
            }
          }
      }

    case ServerCommand("getconfirmedbalance", arr) =>
      GetBalance.fromJsArr(arr) match {
        case Failure(exception) =>
          reject(ValidationRejection("failure", Some(exception)))
        case Success(GetBalance(isSats)) =>
          complete {
            wallet.getConfirmedBalance().map { balance =>
              Server.httpSuccess(
                if (isSats) {
                  balance.satoshis.toString
                } else {
                  Bitcoins(balance.satoshis).toString
                }
              )
            }
          }
      }

    case ServerCommand("getunconfirmedbalance", arr) =>
      GetBalance.fromJsArr(arr) match {
        case Failure(exception) =>
          reject(ValidationRejection("failure", Some(exception)))
        case Success(GetBalance(isSats)) =>
          complete {
            wallet.getUnconfirmedBalance().map { balance =>
              Server.httpSuccess(
                if (isSats) {
                  balance.satoshis.toString
                } else {
                  Bitcoins(balance.satoshis).toString
                }
              )
            }
          }
      }

    case ServerCommand("getnewaddress", _) =>
      complete {
        wallet.getNewAddress().map { address =>
          Server.httpSuccess(address)
        }
      }

    case ServerCommand("sendtoaddress", arr) =>
      // TODO create custom directive for this?
      SendToAddress.fromJsArr(arr) match {
        case Failure(exception) =>
          reject(ValidationRejection("failure", Some(exception)))
        case Success(
              SendToAddress(address, bitcoins, satoshisPerVirtualByteOpt)) =>
          complete {
            for {
              tx <- wallet.sendToAddress(address,
                                         bitcoins,
                                         satoshisPerVirtualByteOpt)
              _ <- wallet.broadcastTransaction(tx)
            } yield {
              Server.httpSuccess(tx.txIdBE)
            }
          }
      }

    case ServerCommand("sendfromoutpoints", arr) =>
      SendFromOutpoints.fromJsArr(arr) match {
        case Failure(exception) =>
          reject(ValidationRejection("failure", Some(exception)))
        case Success(
              SendFromOutpoints(outPoints,
                                address,
                                bitcoins,
                                satoshisPerVirtualByteOpt)) =>
          complete {
            for {
              tx <- wallet.sendFromOutPoints(outPoints,
                                             address,
                                             bitcoins,
                                             satoshisPerVirtualByteOpt)
              _ <- wallet.broadcastTransaction(tx)
            } yield Server.httpSuccess(tx.txIdBE)
          }
      }

    case ServerCommand("sendwithalgo", arr) =>
      SendWithAlgo.fromJsArr(arr) match {
        case Failure(exception) =>
          reject(ValidationRejection("failure", Some(exception)))
        case Success(
              SendWithAlgo(address,
                           bitcoins,
                           satoshisPerVirtualByteOpt,
                           algo)) =>
          complete {
            for {
              tx <- wallet.sendWithAlgo(address,
                                        bitcoins,
                                        satoshisPerVirtualByteOpt,
                                        algo)
              _ <- wallet.broadcastTransaction(tx)
            } yield Server.httpSuccess(tx.txIdBE)
          }
      }

    case ServerCommand("opreturncommit", arr) =>
      OpReturnCommit.fromJsArr(arr) match {
        case Failure(exception) =>
          reject(ValidationRejection("failure", Some(exception)))
        case Success(
              OpReturnCommit(message,
                             hashMessage,
                             satoshisPerVirtualByteOpt)) =>
          complete {
            for {
              tx <- wallet.makeOpReturnCommitment(message,
                                                  hashMessage,
                                                  satoshisPerVirtualByteOpt)
              _ <- wallet.broadcastTransaction(tx)
            } yield {
              Server.httpSuccess(tx.txIdBE)
            }
          }
      }

    case ServerCommand("rescan", arr) =>
      Rescan.fromJsArr(arr) match {
        case Failure(exception) =>
          reject(ValidationRejection("failure", Some(exception)))
        case Success(
              Rescan(batchSize,
                     startBlock,
                     endBlock,
                     force,
                     ignoreCreationTime)) =>
          complete {
            val res = for {
              empty <- wallet.isEmpty()
              msg <-
                if (force || empty) {
                  wallet
                    .rescanNeutrinoWallet(
                      startOpt = startBlock,
                      endOpt = endBlock,
                      addressBatchSize =
                        batchSize.getOrElse(wallet.discoveryBatchSize),
                      useCreationTime = !ignoreCreationTime)
                    .map(_ => "scheduled")
                } else {
                  Future.successful(
                    "DANGER! The wallet is not empty, however the rescan " +
                      "process destroys all existing records and creates new ones. " +
                      "Use force option if you really want to proceed. " +
                      "Don't forget to backup the wallet database.")
                }
            } yield msg
            res.map(msg => Server.httpSuccess(msg))
          }
      }

    case ServerCommand("getutxos", _) =>
      complete {
        wallet.listUtxos().map { utxos =>
          val retStr = utxos.foldLeft("")((accum, spendInfo) =>
            accum + s"${spendInfo.outPoint.hex} ${spendInfo.output.value}\n")
          Server.httpSuccess(retStr)
        }
      }

    case ServerCommand("getaddresses", _) =>
      complete {
        wallet.listAddresses().map { addressDbs =>
          val addresses = addressDbs.map(_.address)
          Server.httpSuccess(addresses)
        }
      }

    case ServerCommand("getspentaddresses", _) =>
      complete {
        wallet.listSpentAddresses().map { addressDbs =>
          val addresses = addressDbs.map(_.address)
          Server.httpSuccess(addresses)
        }
      }

    case ServerCommand("getfundedaddresses", _) =>
      complete {
        wallet.listFundedAddresses().map { addressDbs =>
          val addressAndValues = addressDbs.map {
            case (addressDb, value) => s"${addressDb.address} $value"
          }

          Server.httpSuccess(addressAndValues)
        }
      }

    case ServerCommand("getunusedaddresses", _) =>
      complete {
        wallet.listUnusedAddresses().map { addressDbs =>
          val addresses = addressDbs.map(_.address)
          Server.httpSuccess(addresses)
        }
      }

    case ServerCommand("getaccounts", _) =>
      complete {
        wallet.listAccounts().map { accounts =>
          val xpubs = accounts.map(_.xpub)
          Server.httpSuccess(xpubs)
        }
      }

    case ServerCommand("createnewaccount", _) =>
      complete {
        for {
          newWallet <- wallet.createNewAccount(wallet.keyManager.kmParams)
          accounts <- newWallet.listAccounts()
        } yield {
          val xpubs = accounts.map(_.xpub)
          Server.httpSuccess(xpubs)
        }
      }

  }
}
