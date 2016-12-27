package pl.edu.agh.reactive.akkaFSM

import akka.actor.{Actor, Props, Terminated}
import akka.event.LoggingReceive
import akka.routing._
import pl.edu.agh.reactive.akkaFSM.AuctionSearch.{Auctions, Register, Unregister}
import scala.concurrent.duration._

class MasterSearch extends Actor {
  val numberOfRoutees: Int = 15

  val routees = Vector.fill(numberOfRoutees) {
    val r = context.actorOf(Props[AuctionSearch])
    context watch r
    ActorRefRoutee(r)
  }


  var registerRouter = {
    Router(BroadcastRoutingLogic(), routees)
  }

  var searchRouter = {
    Router(RoundRobinRoutingLogic(), routees)
  }

  override def receive = LoggingReceive {
    case Register(auctionName)  =>
      registerRouter.route(Register(auctionName), sender())
    case Auctions(keyword)  =>
      searchRouter.route(Auctions(keyword) , sender())
    case Unregister =>
      registerRouter.route(Unregister, sender())
    case Terminated(a) =>
      registerRouter = registerRouter.removeRoutee(a)
      searchRouter = searchRouter.removeRoutee(a)
      if (registerRouter.routees.isEmpty && searchRouter.routees.isEmpty)
        context.system.terminate
  }
}
