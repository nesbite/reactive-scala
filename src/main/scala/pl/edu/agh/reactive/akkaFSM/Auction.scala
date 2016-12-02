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
  var stateDuration: Long = 0
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
//      println(s"\t[${self.path.name}] state changed to 'Created'")
//      println(s"\t[${self.path.name}] BidTimer set to $bidTimer")
      goto(AuctionCreated) applying AuctionEventImpl(AuctionDataInitialized(0, sender.path, 0)) andThen {
        case _ =>
          sender ! "Done"
      }

    case Event(_, _) =>
      stay
  }

  when(AuctionCreated){
    case Event(Bid(amount), AuctionDataInitialized(value, seller, timeout)) =>
      if(amount > value){
//        println(s"\t[${self.path.name}][" + self.path.parent.name + "]" + " Bid received: " + amount + " from " + sender.path.name)
//        println(s"\t[${self.path.name}] state changed to 'Activated'")
//        val buyersList =  List(from)
        goto(AuctionActivated) applying AuctionEventImpl(AuctionDataActivated(amount, sender.path, seller, List(sender.path), 0))
      } else {
        sender ! Buyer.OfferRaised(value)
        stay applying AuctionEventImpl(AuctionDataActivated(amount, ActorRef.noSender.path, seller, List(sender.path), System.currentTimeMillis() - stateDuration))
      }

    case Event(Bid(amount), AuctionDataActivated(value, buyer, seller, buyers, timeout)) =>
      var buyersList = buyers
      if(!buyersList.contains(sender.path)){
        buyersList = sender.path :: buyersList
      }
      if(amount > value){
//        println(s"\t[${self.path.name}][" + self.path.parent.name + "]" + " Bid received: " + amount + " from " + sender.path.name)
        //        println(s"\t[${self.path.name}] state changed to 'Activated'")
        goto(AuctionActivated) applying AuctionEventImpl(AuctionDataActivated(amount, sender.path, seller, buyersList, 0))
      } else {
        if(sender.path != buyer) {
          sender ! Buyer.OfferRaised(value)
        }
        if(buyersList != buyers){
          stay applying AuctionEventImpl(AuctionDataActivated(value, buyer, seller, buyersList, System.currentTimeMillis() - stateDuration))
        } else {
          stay
        }
      }

    case Event(Expire, AuctionDataInitialized(value, seller, timeout)) =>
//      println(s"\t[${self.path.name}] expired with no offers")
//      println(s"\t[${self.path.name}] state changed to 'Ignored'")
      val auctionSearch = getMasterSearchActor
      auctionSearch ! AuctionSearch.Unregister
//      println(s"\t[${self.path.name}] DeleteTimer set to $deleteTimer")
      goto(AuctionIgnored) applying AuctionEventImpl(AuctionDataInitialized(value, seller, 0))

    case Event(_, _) =>
      stay
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
    case Event(_, _) =>
      stay
  }

  when(AuctionActivated){
    case Event(Bid(amount), AuctionDataActivated(value, buyer, seller, buyers, timeout)) =>
      var buyersList = buyers
      if(!buyersList.contains(sender.path)){
        buyersList = sender.path :: buyersList
      }
      if(amount > value){
        /*******************
        //AUCTION TERMINATION
        *******************/
//        if(amount > 500){
//          context.system.terminate()
//        }

//        println(s"\t[${self.path.name}][${self.path.parent.name}] Bid received: $amount from ${sender.path.name}")
        for(b <- buyersList.filter(_ != sender.path)){
          context.actorSelection(b) ! Buyer.OfferRaised(amount)
        }
        stay applying AuctionEventImpl(AuctionDataActivated(amount, sender.path, seller, buyersList, System.currentTimeMillis() - stateDuration))
      } else {
        if(sender.path != buyer) {
          sender ! Buyer.OfferRaised(value)
        }
        if(buyersList != buyers){
          stay applying AuctionEventImpl(AuctionDataActivated(value, buyer, seller, buyersList, System.currentTimeMillis() - stateDuration))
        } else {
          stay
        }
      }
    case Event(Expire, AuctionDataActivated(value, buyer, seller, buyers, timeout)) =>
      println(s"\t[${self.path.name}][${self.path.parent.name}] Auction finished. $auctionName sold to ${buyer.name} for $value")
//      println(s"\t[${self.path.name}] state changed to 'Sold'")
      context.actorSelection(buyer) ! Buyer.Won(auctionName, value)
      for( b <- buyers.filter(_ != buyer)){
        context.actorSelection(b) ! Buyer.Lost(auctionName)
      }
      context.actorSelection(seller) ! Seller.Sold(auctionName, value, buyer.name)
//      println(s"\t[${self.path.name}] DeleteTimer set to $deleteTimer)
      context.actorSelection("../MasterSearch") ! AuctionSearch.Unregister

      goto(AuctionSold) applying AuctionEventImpl(AuctionDataActivated(value, buyer, seller, buyers, 0))
    case Event(_, _) =>
      stay
  }

  when(AuctionSold){
    case Event(Delete, AuctionDataActivated(value, buyer, seller, buyers, timeout)) =>
//      println(s"\t[${self.path.name}][${self.path.parent.name}] DeleteTimer expired. Terminating...")
      deleteMessages(Long.MaxValue)
      stop()
    case Event(_, _) =>
      stay
  }

//  initialize()
  onTermination {
    case StopEvent(PersistentFSM.Normal, state, data)         => context.system.terminate
//    case StopEvent(PersistentFSM.Normal, state, data)         => context.stop(self)
    case StopEvent(PersistentFSM.Shutdown, state, data)       => context.system.terminate
//    case StopEvent(PersistentFSM.Shutdown, state, data)       => context.stop(self)
    case StopEvent(PersistentFSM.Failure(cause), state, data) => context.system.terminate
//    case StopEvent(PersistentFSM.Failure(cause), state, data) => context.stop(self)
  }
  onTransition {
    case _ -> (AuctionSold | AuctionIgnored)  =>
      stateDuration = System.currentTimeMillis()
//      println(s"\tDeleteTimer set to $deleteTimer milliseconds")
      timer = context.system.scheduler.scheduleOnce(deleteTimer.milliseconds, self, Delete)
    case _ -> (AuctionCreated)  =>
      stateDuration = System.currentTimeMillis()
//      println(s"\tBidTimer set to $bidTimer milliseconds")
      timer = context.system.scheduler.scheduleOnce(bidTimer.milliseconds, self, Expire)
    case _ -> _ =>
      stateDuration = System.currentTimeMillis()
  }


  override def applyEvent(domainEvent: AuctionEvent, currentData: AuctionData): AuctionData = {
//    println(domainEvent)

    domainEvent match {
      case AuctionEventImpl(data:AuctionData) =>
      data match {
        case AuctionDataUninitialized =>
//          println(s"\t[${self.path.name}] Data set to AuctionDataUninitialized")
        case AuctionDataInitialized(value, seller, stateTime) =>
          setNewTimer(stateTime)
//          println(s"\t[${self.path.name}] Data set to: AuctionDataInitialized($value, ${seller.name}, $stateTime)")
        case AuctionDataActivated(value, buyer, seller, buyers, stateTime) =>
          context.actorSelection("../notifier") ! Notify(auctionName, buyer, value)
          setNewTimer(stateTime)
//          println(s"\t[${self.path.name}] Data set to AuctionDataActivated($value, ${buyer.name}, ${seller.name}, ${buyers.map(b => b.name)}, $stateTime) ")
      }
        data
    }
  }

  def setNewTimer(stateTime: Long): Unit = {
    if(timer != null){
      timer.cancel()
    }
    this.stateName match {
      case AuctionCreated | AuctionActivated =>
//        println(s"\t[${self.path.name}] BidTimer set to ${bidTimer - stateTime} milliseconds")
        timer = context.system.scheduler.scheduleOnce((bidTimer - stateTime).milliseconds, self, Expire)
      case AuctionIgnored | AuctionSold =>
//        println(s"\t[${self.path.name}] DeleteTimer set to ${deleteTimer - stateTime} milliseconds")
        timer = context.system.scheduler.scheduleOnce((deleteTimer - stateTime).milliseconds, self, Delete)
      case AuctionInitState =>
    }
  }

  override def receiveRecover: Receive = LoggingReceive {
    case RecoveryCompleted =>
      this.stateData match {
        case AuctionDataInitialized(value, seller, stateTime) =>
          stateDuration = System.currentTimeMillis() - stateTime
          context.actorSelection(seller) ! "Done"
        case AuctionDataActivated(value, buyer, seller, buyers, stateTime) =>
          stateDuration = System.currentTimeMillis() - stateTime
          context.actorSelection(seller) ! "Done"
        case _ =>
          stateDuration = System.currentTimeMillis()
      }

    case a:Any =>
      super.receiveRecover.apply(a)
  }
}


