package pl.edu.agh.reactive.akkaFSM

import akka.actor.{ActorRef, FSM}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Random

sealed trait BuyerState
case object BuyerInitState extends BuyerState
case object BuyerWaitForAuctions extends BuyerState

sealed trait BuyerData
case object BuyerDataUninitialized extends BuyerData
case class BuyerDataInitialized(maxOffer:BigInt) extends BuyerData

object Buyer {
  case class Won(auctionName:String, amount:BigInt)
  case class Lost(auctionName:String)
  case class Bid(keyword:String, maxOffer:BigInt)
  case class Auctions(auctions:List[ActorRef])
  case class OfferRaised(value:BigInt)
}

class Buyer() extends FSM[BuyerState, BuyerData] {
  import Buyer._


  def getAuctionSearchActor = Await.result(context.actorSelection("../AuctionSearch").resolveOne()(1.seconds), 1.seconds)

  startWith(BuyerInitState, BuyerDataUninitialized)

  when(BuyerInitState){
    case Event(Bid(keyword, maxOffer), _) =>
      val auctionSearch = getAuctionSearchActor
      auctionSearch ! AuctionSearch.Auctions(keyword)
      goto(BuyerWaitForAuctions) using BuyerDataInitialized(maxOffer)

  }

  when(BuyerWaitForAuctions, stateTimeout = 10.seconds){
    case Event(Auctions(auctions), BuyerDataInitialized(maxOffer)) =>
      if(auctions.nonEmpty){
        for(auction <- auctions){
          val amount = Random.nextInt(maxOffer.intValue())
          auction ! Auction.Bid(amount)
        }
      }
      stay using BuyerDataInitialized(maxOffer)

    case Event(OfferRaised(value), BuyerDataInitialized(maxOffer)) =>
      if(value + 10 <= maxOffer){
        sender ! Auction.Bid(value + 10)
      }
      stay using BuyerDataInitialized(maxOffer)

    case Event(Lost(auctionName), BuyerDataInitialized(maxOffer)) =>
      println(s"\t[${self.path.name}] You lost $auctionName")
      stay using BuyerDataInitialized(maxOffer)

    case Event(Won(auctionName, amount), BuyerDataInitialized(maxOffer)) =>
      println(s"\t[${self.path.name}] You won $auctionName for $amount")
      stay using BuyerDataInitialized(maxOffer)
    case Event(StateTimeout, _) =>
      goto(BuyerInitState) using BuyerDataUninitialized
  }

}
