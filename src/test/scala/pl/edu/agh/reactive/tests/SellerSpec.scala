package pl.edu.agh.reactive.tests

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import org.scalatest.{BeforeAndAfterAll, WordSpecLike}
import pl.edu.agh.reactive.akkaFSM.Seller

class SellerSpec extends TestKit(ActorSystem("SellerSpec"))
  with WordSpecLike with BeforeAndAfterAll with ImplicitSender {

  override def afterAll(): Unit = {
    system.terminate
  }

  "A Seller" must {
    "be notified about selling an item" in {
      val probe = TestProbe()
      val seller = system.actorOf(Props(new Seller(List())))
      seller ! Seller.Sold("auctionName", BigInt(10), probe.ref)
      expectMsg("\t[auctionName]You sold auctionName for 10 to " + probe.ref.path.name)
    }

    "be notified about auction that expired" in {
      val seller = system.actorOf(Props(new Seller(List())))
      seller ! Seller.Expired("auctionName")
      expectMsg("\t[auctionName]auctionName expired with no offers")
    }
  }
}
