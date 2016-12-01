package pl.edu.agh.reactive.akkaFSM

import akka.actor.Actor
import akka.event.LoggingReceive

class AuctionPublisher extends Actor {
  override def receive = LoggingReceive {
    case Notify(auctionName, buyer, price) =>
      println(s"[AuctionPublisher][$auctionName] Buyer: ${buyer.name}: - bid: $price")
  }
}
