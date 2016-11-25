package pl.edu.agh.reactive.akkaFSM

import akka.actor.{Actor, ActorRef, ActorRefFactory, Props}
import akka.event.LoggingReceive

object Seller {
  case class Sold(auctionName:String, amount:BigInt, buyer: String)
  case class Expired(auctionName:String)
  case object Sell
}

class Seller(auctions: List[String], auctionFactory: (String) => ActorRef) extends Actor {
  import Seller._

  def receive = LoggingReceive {
    case Sold(auctionName, amount, buyer) =>
      println("\t[" + self.path.name + "]" + " You sold " + auctionName + " for " + amount + " to " + buyer)
    case Expired(auctionName) =>
      println("\t[" + self.path.name + "] " + auctionName + "expired with no offers")
    case Sell =>
      for(i <- auctions.indices) {
        val auction = auctionFactory(auctions(i))
//        val auction = context.actorOf(Props[Auction], auctions(i).replace(" ", "_"))
        auction ! Auction.Create
//        println("\t[" + self.path.name + "]" + "Selling " + auctions(i))
      }
  }
}
