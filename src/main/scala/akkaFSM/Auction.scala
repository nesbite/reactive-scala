package akkaFSM

import akka.actor.{Actor, ActorRef, ActorSystem, FSM, Props}
import akka.event.LoggingReceive

import scala.concurrent._
import scala.concurrent.duration._
import ExecutionContext.Implicits.global
import scala.util.Random

sealed trait State
case object Init extends State
case object Created extends State
case object Activated extends State
case object Ignored extends State
case object Sold extends State

sealed trait Data
case object Uninitialized extends Data
final case class Initialized(value:BigInt, buyer:ActorRef) extends Data

object Auction {
  case class Bid(from: ActorRef, amount: BigInt)
  case object Create
  case object Delete
  case object Expire
  case object ReList
}

class Auction(itemName:String) extends FSM[State, Data] {
  import Auction._

  startWith(Init, Uninitialized)

  when(Init){
    case Event(Create, Uninitialized) =>
      println("\t[" + self.path.name + "]" + " state changed to 'Created'")
      val bidTimer = 5000 milliseconds

      println("\t[" + self.path.name + "]" + " BidTimer set to " + bidTimer)
      context.system.scheduler.scheduleOnce(bidTimer, self, Expire)
      goto(Created) using Initialized(0, context.actorOf(Props[Buyer], "tempBuyer"))

  }

  when(Created){
    case Event(Bid(from, amount), Initialized(value, buyer)) =>
      if(amount > value){
        println("\t[" + self.path.name + "]" + "Bid received: " + amount + " from " + from.path.name)
        println("\t[" + self.path.name + "]" + " state changed to 'Activated'")
        goto(Activated) using Initialized(amount, from)
      } else {
        stay
      }
    case Event(Expire, _) =>
      println("\t[" + self.path.name + "]" + " expired with no offers")
      println("\t[" + self.path.name + "]" + " state changed to 'Ignored'")
      val deleteTimer = 1000 milliseconds

      println("\t[" + self.path.name + "]" + "DeleteTimer set to " + deleteTimer)
      context.system.scheduler.scheduleOnce(deleteTimer, self, Delete)
      goto(Ignored)
  }

  when(Ignored){
    case Event(ReList, Initialized(buyer, value)) =>
      println("\t[" + self.path.name + "]" + "Item re-listed. Auction state changed to 'Activated'")
      goto(Activated) using Initialized(buyer, value)
    case Event(Delete, _) =>
      println("\t[" + self.path.name + "]" + " DeleteTimer expired. Terminating...")
      stop()
  }

  when(Activated){
    case Event(Bid(from, amount), Initialized(value, buyer)) =>
      if(amount > value){
        println("\t[" + self.path.name + "]" + "Bid received: " + amount + " from " + from.path.name)
        stay using Initialized(amount, from)
      } else {
        println("\t[" + self.path.name + "]" + "Bid(" + amount + ") lower than current highest(" + value + ") from " + from.path.name)
        stay using Initialized(value, buyer)
      }
    case Event(Expire, Initialized(value, buyer)) =>
      println("\t[" + self.path.name + "]" + " Auction finished. " + itemName + " sold to " + buyer.path.name + " for " + value)
      println("\t[" + self.path.name + "]" + " state changed to 'Sold'")
      buyer ! Buyer.Won(itemName, value)
      val deleteTimer = 1000 milliseconds

      println("\t[" + self.path.name + "]" + "DeleteTimer set to " + deleteTimer)
      context.system.scheduler.scheduleOnce(deleteTimer, self, Delete)
      goto(Sold) using Initialized(value, buyer)
  }

  when(Sold){
    case Event(Delete, _) =>
      println("\t[" + self.path.name + "]" + " DeleteTimer expired. Terminating...")
      stop()
  }

  onTermination {
    case StopEvent(FSM.Normal, state, data)         => context.system.terminate
    case StopEvent(FSM.Shutdown, state, data)       => context.system.terminate
    case StopEvent(FSM.Failure(cause), state, data) => context.system.terminate
  }

  initialize()

}
object Buyer {
//  case class Bid(auction:ActorRef, amount:BigInt)
  case class Won(itemName:String, amount:BigInt)
  case object Bid
}

class Buyer(auctions: List[ActorRef]) extends Actor {
  import Buyer._

  def this(){
    this(Nil)
  }

  def receive = LoggingReceive {
    case Won(itemName, amount) =>
      println("\t[" + self.path.name + "]" + "You won " + itemName + " for " + amount)
//    case Bid(auction, amount) =>
//      println("\t[" + self.path.name + "]" + "Bidding in " + auction.path.name + " for " + amount)
//      auction ! Auction.Bid(self, amount)
    case Bid =>
      for(i <- 0 to 4) {
        val auction = auctions(Random.nextInt(auctions.length))
        val amount = Random.nextInt(400)
        println("\t[" + self.path.name + "]" + "Bidding in " + auction.path.name + " for " + amount)
        auction ! Auction.Bid(self, amount)
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
      val auction1 = context.actorOf(Props(new Auction("notebook")), "auction1")
      val auction2 = context.actorOf(Props(new Auction("smartphone")), "auction2")
      val auction3 = context.actorOf(Props(new Auction("bike")), "auction3")
      val auction4 = context.actorOf(Props(new Auction("violin")), "auction4")
      val auctionList:List[ActorRef] = List(auction1, auction2, auction3, auction4)


      auction1 ! Auction.Create
      auction2 ! Auction.Create
      auction3 ! Auction.Create
      auction4 ! Auction.Create


      val buyer1 = context.actorOf(Props(new Buyer(auctionList)), "buyer1")
      val buyer2 = context.actorOf(Props(new Buyer(auctionList)), "buyer2")
      val buyer3 = context.actorOf(Props(new Buyer(auctionList)), "buyer3")

      buyer1 ! Buyer.Bid
      buyer2 ! Buyer.Bid
      buyer3 ! Buyer.Bid

  }
}

object AuctionApp extends App {
  val system = ActorSystem("Reactive2")
  val auctionSystemActor = system.actorOf(Props[AuctionSystem], "mainActor")

  auctionSystemActor ! AuctionSystem.Start
  Await.result(system.whenTerminated, Duration.Inf)
}


