package pl.edu.agh.reactive.ownFSM

import akka.actor.{Actor, ActorRef, ActorSystem, FSM, Props}
import akka.event.LoggingReceive

import scala.concurrent._
import scala.concurrent.duration._
import ExecutionContext.Implicits.global
import scala.util.Random

object Auction {
  case class Bid(from: ActorRef, amount: BigInt)
  case object Create
  case object Delete
  case object Expire
  case object ReList
}


class Auction(itemName:String) extends Actor {
  import Auction._

  var value = BigInt(0)
  var buyer:ActorRef = context.actorOf(Props[Buyer], "childName")

  def receive = init

  def init:Receive = LoggingReceive {
    case Create =>
      println("\t[" + self.path.name + "]" + " state changed to 'Created'")
      val bidTimer = 5000 milliseconds

      println("\t[" + self.path.name + "]" + " BidTimer set to " + bidTimer)
      context.system.scheduler.scheduleOnce(bidTimer, self, Expire)
      context become created

  }

  def created:Receive = LoggingReceive {
    case Bid(from, amount) if amount > value =>
      println("\t[" + self.path.name + "]" + "Bid received: " + amount + " from " + from.path.name)
      println("\t[" + self.path.name + "]" + " state changed to 'Activated'")
      buyer = from
      value = amount
      context become activated
    case Expire =>
      println("\t[" + self.path.name + "]" + " expired with no offers")
      println("\t[" + self.path.name + "]" + " state changed to 'Ignored'")
      val deleteTimer = 1000 milliseconds

      println("\t[" + self.path.name + "]" + "DeleteTimer set to " + deleteTimer)
      context.system.scheduler.scheduleOnce(deleteTimer, self, Delete)
      context become ignored
  }

  def activated:Receive = LoggingReceive {
    case Bid(from, amount) if amount > value =>
      println("\t[" + self.path.name + "]" + "Bid received: " + amount + " from " + from.path.name)
      buyer = from
      value = amount

    case Expire =>
      println("\t[" + self.path.name + "]" + " Auction finished. " + itemName + " sold to " + buyer.path.name + " for " + value)
      println("\t[" + self.path.name + "]" + " state changed to 'Sold'")
      buyer ! Buyer.Won(itemName, value)
      val deleteTimer = 1000 milliseconds

      println("\t[" + self.path.name + "]" + "DeleteTimer set to " + deleteTimer)
      context.system.scheduler.scheduleOnce(deleteTimer, self, Delete)
      context become sold
  }

  def ignored:Receive = LoggingReceive {
    case Delete =>
      println("\t[" + self.path.name + "]" + " DeleteTimer expired. Terminating...")
      context.system.terminate
    case ReList =>
      println("\t[" + self.path.name + "]" + "Item re-listed. Auction state changed to 'Activated'")
      context become activated
  }

  def sold:Receive = LoggingReceive {
    case Delete =>
      println("\t[" + self.path.name + "]" + " DeleteTimer expired. Terminating...")
      context.system.terminate
  }

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
        val auction = auctions(Random.nextInt(auctions.length-1))
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


