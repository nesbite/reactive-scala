package pl.edu.agh.reactive.akkaFSM

import akka.actor.{Actor, ActorRef}
import akka.event.LoggingReceive

object Seller {
  case class Sold(auctionName:String, amount:BigInt, buyer: String)
  case class Expired(auctionName:String)
  case class SellTestScenario(numberOfAuctions: BigInt)
  case object Sell
}

class Seller(auctionNames: List[String], auctionFactory: (String) => ActorRef) extends Actor {
  import Seller._

  def receive = LoggingReceive {
    case Sold(auctionName, amount, buyer) =>
      println(s"\t[${self.path.name}] You sold $auctionName for $amount to $buyer")
    case Expired(auctionName) =>
      println(s"\t[${self.path.name}] $auctionName expired with no offers")
    case Sell =>
      for(i <- auctionNames.indices) {
        val auction = auctionFactory(auctionNames(i))
        auction ! Auction.Create
      }
  }
}
