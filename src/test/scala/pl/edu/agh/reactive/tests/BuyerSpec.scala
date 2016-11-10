package pl.edu.agh.reactive.tests

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestFSMRef, TestKit, TestProbe}
import org.scalatest.{BeforeAndAfterAll, WordSpecLike}
import pl.edu.agh.reactive.akkaFSM._

class BuyerSpec extends TestKit(ActorSystem("BuyerSpec"))
  with WordSpecLike with BeforeAndAfterAll with ImplicitSender {

  override def afterAll(): Unit = {
    system.terminate
  }

  "A Buyer" must {
    "search for auctions" in {
      val auctionSearch = TestActorRef(new AuctionSearch, "AuctionSearch")
      val probe = TestProbe()
      val buyer = TestActorRef(new Buyer{
        override def getAuctionSearchActor = probe.ref
      })
      buyer ! Buyer.Bid("auctionName", 100)
      probe.expectMsg(AuctionSearch.Auctions("auctionName"))
    }

    "bid in received auctions" in {
      val probe = TestProbe()
      val buyer = TestFSMRef(new Buyer())
      buyer.setState(BuyerWaitForAuctions, BuyerDataInitialized(100))
      buyer ! Buyer.Auctions(List(probe.ref))
      probe.expectMsgPF() {
        case Auction.Bid(buyer, _) => ()
      }
    }

    "make a new bid when his offer is not highest" in {
      val buyer = TestFSMRef(new Buyer())
      buyer.setState(BuyerWaitForAuctions, BuyerDataInitialized(100))
      buyer ! Buyer.OfferRaised(50)
      expectMsg(Auction.Bid(buyer, 60))
    }
  }
}
