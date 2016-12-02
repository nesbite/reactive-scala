package pl.edu.agh.reactive.akkaFSM

import akka.actor.{Actor, ActorRef}
import akka.event.LoggingReceive

object AuctionSearch {
  case class Auctions(keyword:String)
  case class Register(auctionName:String)
  case object Unregister
}

class AuctionSearch extends Actor {
  import AuctionSearch._
  var auctions:Map[ActorRef, String] = Map()

  def receive = LoggingReceive {
    case Auctions(keyword) =>
//      println(s"\t[${self.path.name}] Keyword $keyword received from ${sender.path.name}")
//      println(s"\t[${self.path.name}] Auctions matching keyword '$keyword': ${auctions.filter(_._2.contains(keyword)).values.toList}")
      sender ! Buyer.Auctions(auctions.filter(_._2.contains(keyword)).keys.toList)
    case Register(auctionName) =>
//      println(s"\t[${self.path.name}] Auction registered: ${sender.path.name}")
      if(!auctions.keySet.contains(sender)){
        auctions += (sender -> auctionName)
      }
    case Unregister =>
//      println(s"\t[${self.path.name}] Auction unregistered: ${sender.path.name}")
      auctions = auctions.filter(_ ne sender)
  }
}
