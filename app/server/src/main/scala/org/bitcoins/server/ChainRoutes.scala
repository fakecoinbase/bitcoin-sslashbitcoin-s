package org.bitcoins.server

import akka.actor.ActorSystem
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import org.bitcoins.chain.api.ChainApi
import org.bitcoins.commons.serializers.Picklers._

case class ChainRoutes(chain: ChainApi)(implicit system: ActorSystem)
    extends ServerRoute {
  import system.dispatcher

  def handleCommand: PartialFunction[ServerCommand, StandardRoute] = {
    case ServerCommand("getblockcount", _) =>
      complete {
        chain.getBlockCount.map { count =>
          Server.httpSuccess(count)
        }
      }
    case ServerCommand("getfiltercount", _) =>
      complete {
        chain.getFilterCount().map { count =>
          Server.httpSuccess(count)
        }
      }
    case ServerCommand("getfilterheadercount", _) =>
      complete {
        chain.getFilterHeaderCount().map { count =>
          Server.httpSuccess(count)
        }
      }
    case ServerCommand("getbestblockhash", _) =>
      complete {
        chain.getBestBlockHash.map { hash =>
          Server.httpSuccess(hash)
        }
      }
  }

}
