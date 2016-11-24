package pl.edu.agh.reactive.akkaFSM

import akka.actor.{ActorRef, FSM}
import akka.persistence.SnapshotOffer
import akka.persistence.fsm.PersistentFSM
import akka.persistence.fsm.PersistentFSM.FSMState
import akka.persistence.fsm.PersistentFSM._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.concurrent.duration._
import scala.reflect._


sealed trait AuctionState extends FSMState
case object AuctionInitState extends AuctionState {
  override def identifier: String = "AuctionInitState"
}
case object AuctionCreated extends AuctionState {
  override def identifier: String = "AuctionCreated"
}
case object AuctionActivated extends AuctionState {
  override def identifier: String = "AuctionActivated"
}
case object AuctionIgnored extends AuctionState {
  override def identifier: String = "AuctionIgnored"
}
case object AuctionSold extends AuctionState {
  override def identifier: String = "AuctionSold"
}

sealed trait AuctionData
case object AuctionDataUninitialized extends AuctionData
final case class AuctionDataInitialized(auctionName:String, value:BigInt, seller:ActorRef) extends AuctionData
final case class AuctionDataActivated(auctionName:String, value:BigInt, buyer:ActorRef, seller:ActorRef, buyers:List[ActorRef]) extends AuctionData

sealed trait AuctionEvent
case class AuctionCreatedEvent(auctionName:String, value:BigInt, seller:ActorRef) extends AuctionEvent
case class AuctionActivatedEvent(auctionName:String, value:BigInt, buyer:ActorRef, seller:ActorRef, buyers:List[ActorRef]) extends AuctionEvent

object Auction {
  case class Bid(from: ActorRef, amount: BigInt)
  case class Create(auctionName:String)
  case object Delete
  case object Expire
  case object ReList
}

class Auction extends PersistentFSM [AuctionState, AuctionData, AuctionEvent] {
  import Auction._

  override def persistenceId = "persistent-toggle-fsm-id-1"
  override def domainEventClassTag: ClassTag[AuctionEvent] = classTag[AuctionEvent]

  def getAuctionSearchActor = Await.result(context.actorSelection("../AuctionSearch").resolveOne()(1.seconds), 1.seconds)

  startWith(AuctionInitState, AuctionDataUninitialized)

  when(AuctionInitState){
    case Event(Create(auctionName), AuctionDataUninitialized) =>
      val auctionSearch = getAuctionSearchActor
      auctionSearch ! AuctionSearch.Register(auctionName)
//      println("\t[" + self.path.name + "]" + " state changed to 'Created'")
      val bidTimer = 5000.milliseconds
//      println("\t[" + self.path.name + "]" + " BidTimer set to " + bidTimer)
      context.system.scheduler.scheduleOnce(bidTimer, self, Expire)
      goto(AuctionCreated) applying AuctionCreatedEvent(auctionName, 0, sender)

  }

  when(AuctionCreated){
    case Event(Bid(from, amount), AuctionDataInitialized(auctionName, value, seller)) =>
      if(amount > value){
        println("\t[" + self.path.name + "]" + "[" + self.path.parent.name + "]" + "Bid received: " + amount + " from " + from.path.name)
//        println("\t[" + self.path.name + "]" + " state changed to 'Activated'")
        val buyersList =  List(from)
        goto(AuctionActivated) applying AuctionActivatedEvent(auctionName, amount, from, seller, buyersList)
      } else {
        stay applying AuctionCreatedEvent(auctionName, value, seller)
      }
    case Event(Expire, AuctionDataInitialized(auctionName, value, seller)) =>
//      println("\t[" + self.path.name + "]" + " expired with no offers")
//      println("\t[" + self.path.name + "]" + " state changed to 'Ignored'")
      val auctionSearch = getAuctionSearchActor
      auctionSearch ! AuctionSearch.Unregister
      val deleteTimer = 5000.milliseconds
//      println("\t[" + self.path.name + "]" + "DeleteTimer set to " + deleteTimer)
      context.system.scheduler.scheduleOnce(deleteTimer, self, Delete)
      goto(AuctionIgnored) applying AuctionCreatedEvent(auctionName, value, seller)
  }

  when(AuctionIgnored){
    case Event(ReList, AuctionDataInitialized(auctionName, value, seller)) =>
      val auctionSearch = getAuctionSearchActor
      auctionSearch ! AuctionSearch.Register(auctionName)
      println("\t[" + self.path.name + "]" + "[" + self.path.parent.name + "]" + "Item re-listed. Auction state changed to 'Created'")
      goto(AuctionCreated) applying AuctionCreatedEvent(auctionName, 0, seller)
    case Event(Delete, AuctionDataInitialized(auctionName, _, seller)) =>
      seller ! Seller.Expired(auctionName)
      println("\t[" + self.path.name + "]" + "[" + self.path.parent.name + "]" + " DeleteTimer expired. Terminating...")
      stop()
  }

  when(AuctionActivated){
    case Event(Bid(from, amount), AuctionDataActivated(auctionName, value, buyer, seller, buyers)) =>
      if(amount > value){
        println("\t[" + self.path.name + "]" + "[" + self.path.parent.name + "]" + "Bid received: " + amount + " from " + from.path.name)
        var buyersList = buyers
        if(!buyers.contains(from)){
          buyersList = List(from) ::: buyers
        }
        for(b <- buyersList.filter(_ ne from)){
          b ! Buyer.OfferRaised(amount)
        }
        stay applying AuctionActivatedEvent(auctionName, amount, from, seller, buyersList)
      } else {
//        println("\t[" + self.path.name + "]" + "Bid(" + amount + ") lower than current highest(" + value + ") from " + from.path.name)
        var buyersList = buyers
        if(!buyers.contains(from)){
          buyersList = List(from) ::: buyers
        }
        from ! Buyer.OfferRaised(value)

        stay applying AuctionActivatedEvent(auctionName, value, buyer, seller, buyersList)
      }
    case Event(Expire, AuctionDataActivated(auctionName, value, buyer, seller, buyers)) =>
      println("\t[" + self.path.name + "]" + "[" + self.path.parent.name + "]" + " Auction finished. " + auctionName + " sold to " + buyer.path.name + " for " + value)
//      println("\t[" + self.path.name + "]" + " state changed to 'Sold'")
      buyer ! Buyer.Won(auctionName, value)
      for( b <- buyers.filter(_ ne buyer)){
        b ! Buyer.Lost(auctionName)
      }
      seller ! Seller.Sold(auctionName, value, buyer)
      val deleteTimer = 5000.milliseconds
//      println("\t[" + self.path.name + "]" + "DeleteTimer set to " + deleteTimer)
      context.system.scheduler.scheduleOnce(deleteTimer, self, Delete)
      val auctionSearch = getAuctionSearchActor
      auctionSearch ! AuctionSearch.Unregister

      goto(AuctionSold) applying AuctionActivatedEvent(auctionName, value, buyer, seller, buyers)
  }

  when(AuctionSold){
    case Event(Delete, AuctionDataActivated(auctionName, value, buyer, seller, buyers)) =>
//      println("\t[" + self.path.name + "]" + "[" + self.path.parent.name + "]" + " DeleteTimer expired. Terminating...")
      stop()
  }

//  initialize()
  onTermination {
    case StopEvent(PersistentFSM.Normal, state, data)         => context.system.terminate
    case StopEvent(PersistentFSM.Shutdown, state, data)       => context.system.terminate
    case StopEvent(PersistentFSM.Failure(cause), state, data) => context.system.terminate
  }

  override val receiveRecover: Receive = {
    case SnapshotOffer(_, snapshot: AuctionState) => println("Snapshot Offer\n")
  }

  override def applyEvent(domainEvent: AuctionEvent, currentData: AuctionData): AuctionData = {
    domainEvent match {
      case AuctionCreatedEvent(auctionName, value, seller) =>
        AuctionDataInitialized(auctionName, value, seller)
      case AuctionActivatedEvent(auctionName, value, buyer, seller, buyers) =>
        AuctionDataActivated(auctionName, value, buyer, seller, buyers)

    }
  }

}


