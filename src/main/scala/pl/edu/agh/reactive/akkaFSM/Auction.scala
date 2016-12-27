package pl.edu.agh.reactive.akkaFSM

import akka.actor.{ActorPath, ActorRef, Cancellable}
import akka.event.LoggingReceive
import akka.persistence.RecoveryCompleted
import akka.persistence.fsm.PersistentFSM
import akka.persistence.fsm.PersistentFSM.FSMState

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
final case class AuctionDataInitialized(value:BigInt, seller:ActorPath, timeout: Long) extends AuctionData
final case class AuctionDataActivated(value:BigInt, buyer:ActorPath, seller:ActorPath, buyers:List[ActorPath], timeout: Long) extends AuctionData

sealed trait AuctionEvent
case class AuctionEventImpl(data:AuctionData) extends AuctionEvent

object Auction {
  case class Bid(amount: BigInt)
  case object Create
  case object Delete
  case object Expire
  case object ReList
}

class Auction(auctionName:String) extends PersistentFSM [AuctionState, AuctionData, AuctionEvent] {
  import Auction._
  var stateStartTime: Long = 0
  var timer: Cancellable = null
  val bidTimer: Long = 5000
  val deleteTimer: Long = 3000

  override def persistenceId = "persistent-toggle-fsm-id-" + auctionName.replace(" ", "-")
  override def domainEventClassTag: ClassTag[AuctionEvent] = classTag[AuctionEvent]

  def getMasterSearchActor = Await.result(context.actorSelection("../MasterSearch").resolveOne()(1.seconds), 1.seconds)
  context.actorSelection("../MasterSearch") ! AuctionSearch.Register(auctionName)

  startWith(AuctionInitState, AuctionDataUninitialized)

  when(AuctionInitState){
    case Event(Create, AuctionDataUninitialized) =>
      goto(AuctionCreated) applying AuctionEventImpl(AuctionDataInitialized(0, sender.path, 0))
  }

  when(AuctionCreated){
    case Event(Bid(amount), AuctionDataInitialized(value, seller, timeout)) =>
      if(amount > value){
        goto(AuctionActivated) applying AuctionEventImpl(AuctionDataActivated(amount, sender.path, seller, List(sender.path), 0))
      } else {
        sender ! Buyer.OfferRaised(value)
        stay applying AuctionEventImpl(AuctionDataActivated(amount, ActorRef.noSender.path, seller, List(sender.path), System.currentTimeMillis() - stateStartTime))
      }

    case Event(Bid(amount), AuctionDataActivated(value, buyer, seller, buyers, timeout)) =>
      var buyersList = buyers
      if(!buyersList.contains(sender.path)){
        buyersList = sender.path :: buyersList
      }
      if(amount > value){
        goto(AuctionActivated) applying AuctionEventImpl(AuctionDataActivated(amount, sender.path, seller, buyersList, 0))
      } else {
        if(sender.path != buyer) {
          sender ! Buyer.OfferRaised(value)
        }
        if(buyersList != buyers){
          stay applying AuctionEventImpl(AuctionDataActivated(value, buyer, seller, buyersList, System.currentTimeMillis() - stateStartTime))
        }
        else {
          stay
        }
      }

    case Event(Expire, AuctionDataInitialized(value, seller, timeout)) =>
      val auctionSearch = getMasterSearchActor
      auctionSearch ! AuctionSearch.Unregister
      goto(AuctionIgnored) applying AuctionEventImpl(AuctionDataInitialized(value, seller, 0))
  }

  when(AuctionIgnored){
    case Event(ReList, AuctionDataInitialized(value, seller, timeout)) =>
      val auctionSearch = getMasterSearchActor
      timer.cancel()
      auctionSearch ! AuctionSearch.Register(auctionName)
      println(s"\t[${self.path.name}][" + self.path.parent.name + "]" + " Item re-listed. Auction state changed to 'Created'")
      goto(AuctionCreated) applying AuctionEventImpl(AuctionDataInitialized(0, seller, 0))
    case Event(Delete, AuctionDataInitialized(_, seller, timeout)) =>
      context.actorSelection(seller) ! Seller.Expired(auctionName)
      println(s"\t[${self.path.name}][" + self.path.parent.name + "]" + " DeleteTimer expired. Terminating...")
      deleteMessages(Long.MaxValue)
      stop()
  }

  when(AuctionActivated){
    case Event(Bid(amount), AuctionDataActivated(value, buyer, seller, buyers, timeout)) =>
      var buyersList = buyers
      if(!buyersList.contains(sender.path)){
        buyersList = sender.path :: buyersList
      }
      if(amount > value){
        for(b <- buyersList.filter(_ != sender.path)){
          context.actorSelection(b) ! Buyer.OfferRaised(amount)
        }
        stay applying AuctionEventImpl(AuctionDataActivated(amount, sender.path, seller, buyersList, System.currentTimeMillis() - stateStartTime))
      } else {
        if(sender.path != buyer) {
          sender ! Buyer.OfferRaised(value)
        }
        if(buyersList != buyers){
          stay applying AuctionEventImpl(AuctionDataActivated(value, buyer, seller, buyersList, System.currentTimeMillis() - stateStartTime))
        }
        else {
          stay
        }
      }
    case Event(Expire, AuctionDataActivated(value, buyer, seller, buyers, timeout)) =>
      println(s"\t[${self.path.name}][${self.path.parent.name}] Auction finished. $auctionName sold to ${buyer.name} for $value")
      context.actorSelection(buyer) ! Buyer.Won(auctionName, value)
      for( b <- buyers.filter(_ != buyer)){
        context.actorSelection(b) ! Buyer.Lost(auctionName)
      }
      context.actorSelection(seller) ! Seller.Sold(auctionName, value, buyer.name)
      context.actorSelection("../MasterSearch") ! AuctionSearch.Unregister

      goto(AuctionSold) applying AuctionEventImpl(AuctionDataActivated(value, buyer, seller, buyers, 0))
  }

  when(AuctionSold){
    case Event(Delete, AuctionDataActivated(value, buyer, seller, buyers, timeout)) =>
      deleteMessages(Long.MaxValue)
      stop()
  }

  onTermination {
    case StopEvent(PersistentFSM.Normal, state, data)         => context.system.terminate
    case StopEvent(PersistentFSM.Shutdown, state, data)       => context.system.terminate
    case StopEvent(PersistentFSM.Failure(cause), state, data) => context.system.terminate
  }

  onTransition {
    case _ -> (AuctionSold | AuctionIgnored)  =>
      stateStartTime = System.currentTimeMillis()
      timer = context.system.scheduler.scheduleOnce(deleteTimer.milliseconds, self, Delete)
    case _ -> (AuctionCreated)  =>
      stateStartTime = System.currentTimeMillis()
      timer = context.system.scheduler.scheduleOnce(bidTimer.milliseconds, self, Expire)
    case _ -> _ =>
      stateStartTime = System.currentTimeMillis()
  }


  def setNewTimer(stateTime: Long): Unit = {
    if(timer != null){
      timer.cancel()
    }
    this.stateName match {
      case AuctionCreated | AuctionActivated =>
        timer = context.system.scheduler.scheduleOnce((bidTimer - stateTime).milliseconds, self, Expire)
      case AuctionIgnored | AuctionSold =>
        timer = context.system.scheduler.scheduleOnce((deleteTimer - stateTime).milliseconds, self, Delete)
      case AuctionInitState =>
    }
  }

  override def applyEvent(domainEvent: AuctionEvent, currentData: AuctionData): AuctionData = {

    domainEvent match {
      case AuctionEventImpl(data:AuctionData) =>
      data match {
        case AuctionDataUninitialized =>
        case AuctionDataInitialized(value, seller, stateTime) =>
          setNewTimer(stateTime)
        case AuctionDataActivated(value, buyer, seller, buyers, stateTime) =>
          context.actorSelection("../notifier") ! Notify(auctionName, buyer, value)
          setNewTimer(stateTime)
      }
        data
    }
  }


  override def receiveRecover: Receive = LoggingReceive {
    case RecoveryCompleted =>
      this.stateData match {
        case AuctionDataInitialized(value, seller, stateTime) =>
          stateStartTime = System.currentTimeMillis() - stateTime
        case AuctionDataActivated(value, buyer, seller, buyers, stateTime) =>
          stateStartTime = System.currentTimeMillis() - stateTime
        case _ =>
          stateStartTime = System.currentTimeMillis()
      }

    case a:Any =>
      super.receiveRecover.apply(a)
  }
}


