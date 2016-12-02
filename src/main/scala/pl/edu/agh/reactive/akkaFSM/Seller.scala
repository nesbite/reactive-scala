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
  var timer:Long = 0
  def receive = LoggingReceive {
    case Sold(auctionName, amount, buyer) =>
      println(s"\t[${self.path.name}] You sold $auctionName for $amount to $buyer")
    case Expired(auctionName) =>
      println(s"\t[${self.path.name}] $auctionName expired with no offers")
    case Sell =>
      var time = System.currentTimeMillis()
      timer = time
      for(i <- auctions.indices) {
        val auction = auctionFactory(auctions(i))
        auction ! Auction.Create
      }

  }
}
