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
      sender ! Buyer.Auctions(auctions.filter(_._2.contains(keyword)).keys.toList)
    case Register(auctionName) =>
      if(!auctions.keySet.contains(sender)){
        auctions += (sender -> auctionName)
      }
    case Unregister =>
      auctions = auctions.filter(_ ne sender)
  }
}
