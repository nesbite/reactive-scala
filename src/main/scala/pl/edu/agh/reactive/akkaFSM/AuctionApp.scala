package pl.edu.agh.reactive.akkaFSM

import akka.actor.{Actor, ActorRef, ActorRefFactory, ActorSystem, Props}
import akka.event.LoggingReceive
import com.typesafe.config.ConfigFactory

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.concurrent.duration._
import akka.pattern.ask
import akka.util.Timeout

object AuctionSystem {
  case object Start
}

class AuctionSystem extends Actor{
  import AuctionSystem._

  def auctionFactory(actorName:String) : ActorRef = {
    return context.actorOf(Props(new Auction(actorName)), actorName.replace(" ", "_"))
  }


  def receive = LoggingReceive {
    case Start =>
//      println("Creating auctionSearch")
      val masterSearch = context.actorOf(Props[AuctionSearch], "MasterSearch")
      val notifier = context.actorOf(Props[Notifier], "notifier")

//      val auctionList1:List[String] = List("audi a6 diesel manual", "ford focus gas manual", "opel astra diesel manual", "citroen c3 petrol auto")
//      val auctionList1:List[String] = List("audi a6 diesel manual")
//      val auctionList2:List[String] = List("toyota rav4 petrol manual", "honda accord diesel manual", "citroen berlingo petrol manual")
//      val auctionList2:List[String] = List("toyota rav4 petrol manual")
//      println("Creating sellers")
      val seller1 = context.actorOf(Props(new Seller(List(), auctionFactory)), "seller1")
//      val seller2 = context.actorOf(Props(new Seller(auctionList2, auctionFactory)), "seller2")

      implicit val timeout = Timeout(10 minute)
      val future = seller1 ? Seller.SellTestScenario(50000)
      val response = Await.result(future, timeout.duration).asInstanceOf[String]
      println("Auctions registered")
      response match {
        case "Done" =>
          val buyer1 = context.actorOf(Props[Buyer], "buyer1")
          buyer1 ! Buyer.BidTestScenario
      }
//      seller2 ! Seller.Sell

//      println("Creating buyers")
//      Thread.sleep(4000)
      //      println()

//      val buyer2 = context.actorOf(Props[Buyer], "buyer2")
//      val buyer3 = context.actorOf(Props[Buyer], "buyer3")
//      val buyer4 = context.actorOf(Props[Buyer], "buyer4")
//      val buyer5 = context.actorOf(Props[Buyer], "buyer5")
//      val buyer6 = context.actorOf(Props[Buyer], "buyer6")
//

//      buyer2 ! Buyer.Bid("manual", 200)
//      buyer3 ! Buyer.Bid("toyota", 300)
//      buyer4 ! Buyer.Bid("citroen", 900)
//      buyer5 ! Buyer.Bid("citroen", 700)
//      buyer6 ! Buyer.Bid("citroen", 800)

  }
}

object AuctionApp extends App {
  val config = ConfigFactory.load()
  val serverSystem = ActorSystem("AuctionPublisher", config.getConfig("serverapp").withFallback(config))
  val auctionPublisher = serverSystem.actorOf(Props[AuctionPublisher], "auctionPublisher")

  val clientSystem = ActorSystem("Reactive5", config.getConfig("clientapp").withFallback(config))
  val auctionSystemActor = clientSystem.actorOf(Props[AuctionSystem], "mainActor")

  auctionSystemActor ! AuctionSystem.Start

  Await.result(clientSystem.whenTerminated, Duration.Inf)

  serverSystem.terminate()
  Await.result(serverSystem.whenTerminated, Duration.Inf)
}

