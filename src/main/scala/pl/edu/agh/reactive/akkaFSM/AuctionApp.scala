package pl.edu.agh.reactive.akkaFSM

import akka.actor.{Actor, ActorRef, ActorRefFactory, ActorSystem, Props}
import akka.event.LoggingReceive

import scala.concurrent.Await
import scala.concurrent.duration.Duration


object AuctionSystem {
  case object Start
}

class AuctionSystem extends Actor{
  import AuctionSystem._

  def auctionFactory(actorName:String) : ActorRef = {
    return context.actorOf(Props[Auction], actorName)
  }


  def receive = LoggingReceive {
    case Start =>

      val auctionSearch = context.actorOf(Props[AuctionSearch], "AuctionSearch")

      val auctionList1:List[String] = List("audi a6 diesel manual", "ford focus gas manual", "opel astra diesel manual", "citroen c3 petrol auto")
      val auctionList2:List[String] = List("toyota rav4 petrol manual", "honda accord diesel manual", "citroen berlingo petrol manual")
      val seller1 = context.actorOf(Props(new Seller(auctionList1, auctionFactory)), "seller1")
      val seller2 = context.actorOf(Props(new Seller(auctionList2, auctionFactory)), "seller2")


      seller1 ! Seller.Sell
      seller2 ! Seller.Sell

      Thread.sleep(1000)
      val buyer1 = context.actorOf(Props[Buyer], "buyer1")
      val buyer2 = context.actorOf(Props[Buyer], "buyer2")
      val buyer3 = context.actorOf(Props[Buyer], "buyer3")
      val buyer4 = context.actorOf(Props[Buyer], "buyer4")
      val buyer5 = context.actorOf(Props[Buyer], "buyer5")
      val buyer6 = context.actorOf(Props[Buyer], "buyer6")

      buyer1 ! Buyer.Bid("auto", 300)
      buyer2 ! Buyer.Bid("manual", 200)
      buyer3 ! Buyer.Bid("toyota", 300)
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

