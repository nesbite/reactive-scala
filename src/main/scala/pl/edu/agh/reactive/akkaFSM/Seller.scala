package pl.edu.agh.reactive.akkaFSM

import akka.actor.{Actor, ActorRef, ActorRefFactory, Props}
import akka.event.LoggingReceive

object Seller {
  case class Sold(auctionName:String, amount:BigInt, buyer: String)
  case class Expired(auctionName:String)
  case class SellTestScenario(numberOfAuctions: BigInt)
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
      val time = System.currentTimeMillis()
//      println(s"\t[${self.path.name}] Creating auctions: $time")
      timer = time
      for(i <- auctions.indices) {
        val auction = auctionFactory(auctions(i))
//        val auction = context.actorOf(Props[Auction], auctions(i).replace(" ", "_"))
        auction ! Auction.Create
//        println(s"\t[${self.path.name}] Selling ${auctions(i)}")
      }
//      println("After Creating auctions")
    case SellTestScenario(numberOfAuctions) =>
      for(i <- Range(0, numberOfAuctions.intValue())) {
        val auction = auctionFactory(s"auction$i")
//        auction ! Auction.Create
      }
      sender ! "Done"
    case "Done" =>
      var time = System.currentTimeMillis()
//      println(s"\t[${self.path.name}] Auction [${sender.path.name}] created: $time, execution time: ${time - timer}")

  }
}
