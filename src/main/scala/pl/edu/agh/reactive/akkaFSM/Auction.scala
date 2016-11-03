package pl.edu.agh.reactive.akkaFSM

import akka.actor.{Actor, ActorRef, ActorSystem, FSM, Props}
import akka.event.LoggingReceive

import scala.concurrent._
import scala.concurrent.duration._
import ExecutionContext.Implicits.global
import scala.util.Random

sealed trait AuctionState
case object AuctionInit extends AuctionState
case object AuctionCreated extends AuctionState
case object AuctionActivated extends AuctionState
case object AuctionIgnored extends AuctionState
case object AuctionSold extends AuctionState

sealed trait AuctionData
case object Uninitialized extends AuctionData
final case class Initialized(value:BigInt, buyer:ActorRef, seller:ActorRef, buyers:List[ActorRef]) extends AuctionData
final case class InitializedWithBuyers(value:BigInt, buyer:ActorRef, seller:ActorRef, buyers:List[ActorRef]) extends AuctionData

object Auction {
  case class Bid(from: ActorRef, amount: BigInt)
  case object Create
  case object Delete
  case object Expire
  case object ReList
}

class Auction(val auctionName:String) extends FSM[AuctionState, AuctionData] {
  import Auction._

  startWith(AuctionInit, Uninitialized)

  when(AuctionInit){
    case Event(Create, Uninitialized) =>
      val auctionSearch = context.actorSelection("/user/*/AuctionSearch")
      auctionSearch ! AuctionSearch.Register(auctionName)
//      println("\t[" + self.path.name + "]" + " state changed to 'Created'")
      val bidTimer = 5000 milliseconds

//      println("\t[" + self.path.name + "]" + " BidTimer set to " + bidTimer)
      context.system.scheduler.scheduleOnce(bidTimer, self, Expire)
      goto(AuctionCreated) using Initialized(0, context.actorOf(Props[Buyer], "tempBuyer"), sender, List())

  }

  when(AuctionCreated){
    case Event(Bid(from, amount), Initialized(value, buyer, seller, buyers)) =>
      if(amount > value){
        println("\t[" + self.path.name + "]" + "[" + self.path.parent.name + "]" + "Bid received: " + amount + " from " + from.path.name)
//        println("\t[" + self.path.name + "]" + " state changed to 'Activated'")
        var buyersList = buyers
        if(!buyers.contains(from)){
          buyersList = List(from) ::: buyers
        }
        goto(AuctionActivated) using InitializedWithBuyers(amount, from, seller, buyersList)
      } else {
        stay
      }
    case Event(Expire, Initialized(_,_,_,_)) =>
//      println("\t[" + self.path.name + "]" + " expired with no offers")
//      println("\t[" + self.path.name + "]" + " state changed to 'Ignored'")
      val auctionSearch = context.actorSelection("/user/*/AuctionSearch")
      auctionSearch ! AuctionSearch.Unregister
      val deleteTimer = 1000 milliseconds

//      println("\t[" + self.path.name + "]" + "DeleteTimer set to " + deleteTimer)
      context.system.scheduler.scheduleOnce(deleteTimer, self, Delete)

      goto(AuctionIgnored)
  }

  when(AuctionIgnored){
    case Event(ReList, Initialized(value, buyer, seller, buyers)) =>
      val auctionSearch = context.actorSelection("/user/*/AuctionSearch")
      auctionSearch ! AuctionSearch.Register
      println("\t[" + self.path.name + "]" + "[" + self.path.parent.name + "]" + "Item re-listed. Auction state changed to 'Activated'")
      goto(AuctionActivated) using Initialized(value, buyer, seller, buyers)
    case Event(Delete, Initialized(_,_,_,_)) =>
      println("\t[" + self.path.name + "]" + "[" + self.path.parent.name + "]" + " DeleteTimer expired. Terminating...")
      stop()
  }

  when(AuctionActivated){
    case Event(Bid(from, amount), InitializedWithBuyers(value, buyer, seller, buyers)) =>
      if(amount > value){
        println("\t[" + self.path.name + "]" + "[" + self.path.parent.name + "]" + "Bid received: " + amount + " from " + from.path.name)
        var buyersList = buyers
        if(!buyers.contains(from)){
          buyersList = List(from) ::: buyers
        }
        for(b <- buyersList.filter(_ ne from)){
          b ! Buyer.OfferRaised(amount)
        }
        stay using InitializedWithBuyers(amount, from, seller, buyersList)
      } else {
//        println("\t[" + self.path.name + "]" + "Bid(" + amount + ") lower than current highest(" + value + ") from " + from.path.name)
        stay using InitializedWithBuyers(value, buyer, seller, buyers)
      }
    case Event(Expire, InitializedWithBuyers(value, buyer, seller, buyers)) =>
      println("\t[" + self.path.name + "]" + "[" + self.path.parent.name + "]" + " Auction finished. " + auctionName + " sold to " + buyer.path.name + " for " + value)
//      println("\t[" + self.path.name + "]" + " state changed to 'Sold'")
      buyer ! Buyer.Won(auctionName, value)
      for( b <- buyers.filter(_ ne buyer)){
        b ! Buyer.Lost(auctionName)
      }
      seller ! Seller.Sold(auctionName, value, buyer)
      val deleteTimer = 1000 milliseconds

//      println("\t[" + self.path.name + "]" + "DeleteTimer set to " + deleteTimer)
      context.system.scheduler.scheduleOnce(deleteTimer, self, Delete)

      val auctionSearch = context.actorSelection("/user/*/AuctionSearch")
      auctionSearch ! AuctionSearch.Unregister

      goto(AuctionSold) using InitializedWithBuyers(value, buyer, seller, buyers)
  }

  when(AuctionSold){
    case Event(Delete, InitializedWithBuyers(_,_,_,_)) =>
      println("\t[" + self.path.name + "]" + "[" + self.path.parent.name + "]" + " DeleteTimer expired. Terminating...")
      stop()
  }

  onTermination {
    case StopEvent(FSM.Normal, state, data)         => context.system.terminate
    case StopEvent(FSM.Shutdown, state, data)       => context.system.terminate
    case StopEvent(FSM.Failure(cause), state, data) => context.system.terminate
  }

  initialize()

}


object AuctionSearch {
  case class Auctions(keyword:String)
  case class Register(auction:String)
  case object Unregister
}

class AuctionSearch extends Actor {
  import AuctionSearch._
  var auctions:Map[ActorRef, String] = Map()

  def receive = LoggingReceive {
    case Auctions(keyword) =>
      println("\t[" + self.path.name + "]" + "Keyword " + keyword + " received from " + sender.path.name)
      println("\t[" + self.path.name + "]" + "Auctions matching keyword '" + keyword + "': " +auctions.filter(_._2.contains(keyword)).values.toList)
      sender ! Buyer.Auctions(auctions.filter(_._2.contains(keyword)).keys.toList)
    case Register(auction) =>
      println("\t[" + self.path.name + "]" + "Auction registered: " + sender.path.name)
      if(!auctions.keySet.contains(sender)){
        auctions += (sender -> auction)
      }
    case Unregister =>
      println("\t[" + self.path.name + "]" + "Auction unregistered: " + sender.path.name)
      auctions = auctions.filter(_ ne sender)
  }
}


sealed trait BuyerState
case object BuyerInit extends BuyerState
case object BuyerWaitAuctions extends BuyerState
case object BuyerWaitNotifications extends BuyerState

sealed trait BuyerData
case object BuyerUninitialized extends BuyerData
case class BuyerInitialized(maxOffer:BigInt) extends BuyerData

object Buyer {
  case class Won(auctionName:String, amount:BigInt)
  case class Lost(auctionName:String)
  case class Bid(keyword:String, maxOffer:BigInt)
  case class Auctions(auctions:List[ActorRef])
  case class OfferRaised(value:BigInt)
}

class Buyer() extends FSM[BuyerState, BuyerData] {
  import Buyer._

  startWith(BuyerInit, BuyerUninitialized)

  when(BuyerInit){
    case Event(Bid(keyword, maxOffer), _) =>
      Thread.sleep(3000)
      val auctionSearch = context.actorSelection("../AuctionSearch")
      auctionSearch ! AuctionSearch.Auctions(keyword)
      goto(BuyerWaitAuctions) using BuyerInitialized(maxOffer)

  }

  when(BuyerWaitAuctions){
    case Event(Auctions(auctions), BuyerInitialized(maxOffer)) =>
      if(auctions.nonEmpty){
        val amount = Random.nextInt(400)
        println("\t[" + self.path.name + "]" + "Bidding in " + auctions.head.path.name + " for " + amount)
        auctions.head ! Auction.Bid(self, amount)
      }
      goto(BuyerWaitNotifications) using BuyerInitialized(maxOffer)
  }

  when(BuyerWaitNotifications){
    case Event(OfferRaised(value), BuyerInitialized(maxOffer)) =>
      println("\t[" + self.path.name + "]" + "OfferRaised received - value = " + value)
      if(value + 10 <= maxOffer){
        sender ! Auction.Bid(self, value + 10)
      }
      stay using BuyerInitialized(maxOffer)
    case Event(Lost(auctionName), BuyerInitialized(maxOffer)) =>
      println("\t[" + self.path.name + "]" + "You lost " + auctionName)
      goto(BuyerInit) using BuyerUninitialized
    case Event(Won(auctionName, amount), BuyerInitialized(maxOffer)) =>
      println("\t[" + self.path.name + "]" + "You won " + auctionName + " for " + amount)
      goto(BuyerInit) using BuyerUninitialized
  }

}


object Seller {
  case class Sold(auctionName:String, amount:BigInt, buyer: ActorRef)
  case object Sell
}

class Seller(auctions: List[String]) extends Actor {
  import Seller._

  def receive = LoggingReceive {
    case Sold(auctionName, amount, buyer) =>
      println("\t[" + self.path.name + "]" + "You sold " + auctionName + " for " + amount + " to " + buyer.path.name)

    case Sell =>
      for(i <- auctions.indices) {
        val auction = context.actorOf(Props(new Auction(auctions(i))), auctions(i).toString.replace(" ", "_"))
        auction ! Auction.Create
        println("\t[" + self.path.name + "]" + "Selling " + auctions(i))
      }
  }
}

object AuctionSystem {
  case object Start
}

class AuctionSystem extends Actor{
  import AuctionSystem._
  def receive = LoggingReceive {
    case Start =>

      val auctionSearch = context.actorOf(Props[AuctionSearch], "AuctionSearch")

      val auctionList1:List[String] = List("audi a6 diesel manual", "ford focus gas manual", "opel astra diesel manual", "citroen c3 petrol auto")
      val auctionList2:List[String] = List("toyota rav4 petrol manual", "honda accord diesel manual", "citroen berlingo petrol manual")
      val seller1 = context.actorOf(Props(new Seller(auctionList1)), "seller1")
      val seller2 = context.actorOf(Props(new Seller(auctionList2)), "seller2")


      seller1 ! Seller.Sell
      seller2 ! Seller.Sell

      val buyer1 = context.actorOf(Props[Buyer], "buyer1")
      val buyer2 = context.actorOf(Props[Buyer], "buyer2")
      val buyer3 = context.actorOf(Props[Buyer], "buyer3")
      val buyer4 = context.actorOf(Props[Buyer], "buyer4")
      val buyer5 = context.actorOf(Props[Buyer], "buyer5")
      val buyer6 = context.actorOf(Props[Buyer], "buyer6")

      buyer1 ! Buyer.Bid("auto", 300)
      buyer2 ! Buyer.Bid("manual", 200)
      buyer3 ! Buyer.Bid("citroen", 1000)
      buyer4 ! Buyer.Bid("citroen", 900)
      buyer5 ! Buyer.Bid("citroen", 700)
      buyer6 ! Buyer.Bid("citroen", 800)

  }
}

object AuctionApp extends App {
  val system = ActorSystem("Reactive2")
  val auctionSystemActor = system.actorOf(Props[AuctionSystem], "mainActor")

  auctionSystemActor ! AuctionSystem.Start
  Await.result(system.whenTerminated, Duration.Inf)
}


