package pl.edu.agh.reactive.tests

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestFSMRef, TestKit, TestProbe}
import org.scalatest.{BeforeAndAfterAll, WordSpecLike}
import pl.edu.agh.reactive.akkaFSM.{Auction, Seller}

class SellerSpec extends TestKit(ActorSystem("SellerSpec"))
  with WordSpecLike with BeforeAndAfterAll with ImplicitSender {

  override def afterAll(): Unit = {
    system.terminate
  }

  "A Seller" must {
    "create an auction" in {
      val probe = TestProbe()
      val seller = system.actorOf(Props(new Seller(List("auctionName"), _ => probe.ref)), "seller")
      seller ! Seller.Sell
      probe.expectMsg(Auction.Create("auctionName"))
    }
  }
}
