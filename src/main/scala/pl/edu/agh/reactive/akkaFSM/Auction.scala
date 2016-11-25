package pl.edu.agh.reactive.akkaFSM

import akka.actor.{ActorPath, ActorRef, FSM}
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
final case class AuctionDataInitialized(value:BigInt, seller:ActorPath) extends AuctionData
final case class AuctionDataActivated(value:BigInt, buyer:ActorPath, seller:ActorPath, buyers:List[ActorPath]) extends AuctionData

sealed trait AuctionEvent
case class AuctionEventImpl(data:AuctionData) extends AuctionEvent
//case class AuctionActivatedEvent(value:BigInt, buyer:String, seller:String) extends AuctionEvent

object Auction {
  case class Bid(amount: BigInt)
  case object Create
  case object Delete
  case object Expire
  case object ReList
}

class Auction(auctionName:String) extends PersistentFSM [AuctionState, AuctionData, AuctionEvent] {
  import Auction._

  override def persistenceId = "persistent-toggle-fsm-id-" + auctionName.replace(" ", "-")
  override def domainEventClassTag: ClassTag[AuctionEvent] = classTag[AuctionEvent]

  def getAuctionSearchActor = Await.result(context.actorSelection("../AuctionSearch").resolveOne()(1.seconds), 1.seconds)

  context.actorSelection("../AuctionSearch") ! AuctionSearch.Register(auctionName)

  startWith(AuctionInitState, AuctionDataUninitialized)

  when(AuctionInitState){
    case Event(Create, AuctionDataUninitialized) =>
//      println("\t[" + self.path.name + "]" + " state changed to 'Created'")
      val bidTimer = 8000.milliseconds
//      println("\t[" + self.path.name + "]" + " BidTimer set to " + bidTimer)
//      context.system.scheduler.scheduleOnce(bidTimer, self, Expire)
      goto(AuctionCreated) applying AuctionEventImpl(AuctionDataInitialized(0, sender.path))
    case Event(_, _) =>
      stay
  }

  when(AuctionCreated){
    case Event(Bid(amount), AuctionDataInitialized(value, seller)) =>
      if(amount > value){
        println("\t[" + self.path.name + "]" + "[" + self.path.parent.name + "]" + " Bid received: " + amount + " from " + sender.path.name)
//        println("\t[" + self.path.name + "]" + " state changed to 'Activated'")
//        val buyersList =  List(from)
        goto(AuctionActivated) applying AuctionEventImpl(AuctionDataActivated(amount, sender.path, seller, List(sender.path)))
      } else {
        sender ! Buyer.OfferRaised(value)
        stay applying AuctionEventImpl(AuctionDataActivated(amount, ActorRef.noSender.path, seller, List(sender.path)))
      }

    case Event(Bid(amount), AuctionDataActivated(value, buyer, seller, buyers)) =>
      var buyersList = buyers
      if(!buyersList.contains(sender.path)){
        buyersList = sender.path :: buyersList
      }
      if(amount > value){
        println("\t[" + self.path.name + "]" + "[" + self.path.parent.name + "]" + " Bid received: " + amount + " from " + sender.path.name)
        //        println("\t[" + self.path.name + "]" + " state changed to 'Activated'")
        goto(AuctionActivated) applying AuctionEventImpl(AuctionDataActivated(amount, sender.path, seller, buyersList))
      } else {
        if(sender.path != buyer) {
          sender ! Buyer.OfferRaised(value)
        }
        if(buyersList != buyers){
          stay applying AuctionEventImpl(AuctionDataActivated(value, buyer, seller, buyersList))
        } else {
          stay
        }
      }

    case Event(Expire, AuctionDataInitialized(value, seller)) =>
//      println("\t[" + self.path.name + "]" + " expired with no offers")
//      println("\t[" + self.path.name + "]" + " state changed to 'Ignored'")
      val auctionSearch = getAuctionSearchActor
      auctionSearch ! AuctionSearch.Unregister
      val deleteTimer = 5000.milliseconds
//      println("\t[" + self.path.name + "]" + "DeleteTimer set to " + deleteTimer)
//      context.system.scheduler.scheduleOnce(deleteTimer, self, Delete)
      goto(AuctionIgnored) applying AuctionEventImpl(AuctionDataInitialized(value, seller))

    case Event(_, _) =>
      stay
  }

  when(AuctionIgnored){
    case Event(ReList, AuctionDataInitialized(value, seller)) =>
      val auctionSearch = getAuctionSearchActor
      auctionSearch ! AuctionSearch.Register(auctionName)
      println("\t[" + self.path.name + "]" + "[" + self.path.parent.name + "]" + " Item re-listed. Auction state changed to 'Created'")
      goto(AuctionCreated) applying AuctionEventImpl(AuctionDataInitialized(0, seller))
    case Event(Delete, AuctionDataInitialized(_, seller)) =>
      context.actorSelection(seller) ! Seller.Expired(auctionName)
      println("\t[" + self.path.name + "]" + "[" + self.path.parent.name + "]" + " DeleteTimer expired. Terminating...")
      deleteMessages(Long.MaxValue)
      stop()
    case Event(_, _) =>
      stay
  }

  when(AuctionActivated){
    case Event(Bid(amount), AuctionDataActivated(value, buyer, seller, buyers)) =>
      var buyersList = buyers
      if(!buyersList.contains(sender.path)){
        buyersList = sender.path :: buyersList
      }
      if(amount > value){
        println("\t[" + self.path.name + "]" + "[" + self.path.parent.name + "]" + " Bid received: " + amount + " from " + sender.path.name)
        for(b <- buyersList.filter(_ != sender.path)){
          context.actorSelection(b) ! Buyer.OfferRaised(amount)
        }
        stay applying AuctionEventImpl(AuctionDataActivated(amount, sender.path, seller, buyersList))
      } else {
        if(sender.path != buyer) {
          sender ! Buyer.OfferRaised(value)
        }
        if(buyersList != buyers){
          stay applying AuctionEventImpl(AuctionDataActivated(value, buyer, seller, buyersList))
        } else {
          stay
        }
      }
    case Event(Expire, AuctionDataActivated(value, buyer, seller, buyers)) =>
      println("\t[" + self.path.name + "]" + "[" + self.path.parent.name + "]" + " Auction finished. " + auctionName + " sold to " + buyer.name + " for " + value)
//      println("\t[" + self.path.name + "]" + " state changed to 'Sold'")
      context.actorSelection(buyer) ! Buyer.Won(auctionName, value)
      for( b <- buyers.filter(_ != buyer)){
        context.actorSelection(b) ! Buyer.Lost(auctionName)
      }
      context.actorSelection(seller) ! Seller.Sold(auctionName, value, buyer.name)
      val deleteTimer = 5000.milliseconds
//      println("\t[" + self.path.name + "]" + "DeleteTimer set to " + deleteTimer)
//      context.system.scheduler.scheduleOnce(deleteTimer, self, Delete)
      context.actorSelection("../AuctionSearch") ! AuctionSearch.Unregister

      goto(AuctionSold) applying AuctionEventImpl(AuctionDataActivated(value, buyer, seller, buyers))
    case Event(_, _) =>
      stay
  }

  when(AuctionSold){
    case Event(Delete, AuctionDataActivated(value, buyer, seller, buyers)) =>
//      println("\t[" + self.path.name + "]" + "[" + self.path.parent.name + "]" + " DeleteTimer expired. Terminating...")
      deleteMessages(Long.MaxValue)
      stop()
    case Event(_, _) =>
      stay
  }

//  initialize()
  onTermination {
    case StopEvent(PersistentFSM.Normal, state, data)         => context.system.terminate
    case StopEvent(PersistentFSM.Shutdown, state, data)       => context.system.terminate
    case StopEvent(PersistentFSM.Failure(cause), state, data) => context.system.terminate
  }


  override def applyEvent(domainEvent: AuctionEvent, currentData: AuctionData): AuctionData = {
//    println(domainEvent)
    domainEvent match {
      case AuctionEventImpl(data:AuctionData) =>
      data match {
        case AuctionDataUninitialized =>
          println("\t[" +self.path.name + "] Data set to AuctionDataUninitialized")
        case AuctionDataInitialized(value, seller) =>
          println("\t[" +self.path.name + s"] Data set to: AuctionDataInitialized($value, ${seller.name})")
        case AuctionDataActivated(value, buyer, seller, buyers) =>
          println("\t[" +self.path.name + s"] Data set to AuctionDataActivated($value, ${buyer.name}, ${seller.name}, ${buyers.map(b => b.name)}) ")
      }
        data
    }
  }

}


