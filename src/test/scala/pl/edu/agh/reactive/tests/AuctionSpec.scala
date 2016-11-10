package pl.edu.agh.reactive.tests

import akka.actor.{ActorSystem}
import akka.testkit.{ImplicitSender, TestActorRef, TestFSMRef, TestKit, TestProbe}
import org.scalatest.{BeforeAndAfterAll, WordSpecLike}
import pl.edu.agh.reactive.akkaFSM._

class AuctionSpec extends TestKit(ActorSystem("AuctionSpec"))
  with WordSpecLike with BeforeAndAfterAll with ImplicitSender {
  override def afterAll(): Unit = {
    system.terminate
  }

  "An Auction" must {
    "register at AuctionSearch" in {
      val probe = TestProbe()
      val auction = TestActorRef(new Auction{
        override def getAuctionSearchActor = probe.ref
      })
      auction ! Auction.Create("auctionName")
      probe.expectMsg(AuctionSearch.Register("auctionName"))
    }

    "unregister at AuctionSearch" in {
      val probe = TestProbe()
      val auction = TestFSMRef(new Auction{
        override def getAuctionSearchActor = probe.ref
      })
      auction.setState(AuctionCreated, AuctionDataInitialized("auctionName", 0, self))

      auction ! Auction.Expire
      probe.expectMsg(AuctionSearch.Unregister)
    }

    "register at AuctionSearch after relisting" in {
      val probe = TestProbe()
      val sellerProbe = TestProbe()
      val auction = TestFSMRef(new Auction{
        override def getAuctionSearchActor = probe.ref
      })
      auction.setState(AuctionIgnored, AuctionDataInitialized("auctionName", 0, sellerProbe.ref))
      auction ! Auction.ReList
      probe.expectMsg(AuctionSearch.Register("auctionName"))
    }

    "notify participants about auction ending" in {
      val buyerProbe = TestProbe()
      val otherBuyerProbe = TestProbe()
      val sellerProbe = TestProbe()
      val probe = TestProbe()
      val auction = TestFSMRef(new Auction{
        override def getAuctionSearchActor = probe.ref
      })
      auction.setState(AuctionActivated, AuctionDataActivated("auctionName", 50, buyerProbe.ref, sellerProbe.ref, List(buyerProbe.ref, otherBuyerProbe.ref)))
      auction ! Auction.Expire
      buyerProbe.expectMsg(Buyer.Won("auctionName", 50))
      otherBuyerProbe.expectMsg(Buyer.Lost("auctionName"))
      sellerProbe.expectMsg(Seller.Sold("auctionName", 50, buyerProbe.ref))
      probe.expectMsg(AuctionSearch.Unregister)

    }

    "notify users about offer raise" in {
      val buyerProbe = TestProbe()
      val otherBuyerProbe = TestProbe()
      val sellerProbe = TestProbe()
      val auction = TestFSMRef(new Auction)
      auction.setState(AuctionActivated, AuctionDataActivated("auctionName", 50, buyerProbe.ref, sellerProbe.ref, List(buyerProbe.ref, otherBuyerProbe.ref)))
      auction ! Auction.Bid(self, 60)
      buyerProbe.expectMsg(Buyer.OfferRaised(60))
      otherBuyerProbe.expectMsg(Buyer.OfferRaised(60))
    }

    "send a message to seller after auction expiration" in {
      val probe = TestProbe()
      val sellerProbe = TestProbe()
      val auction = TestFSMRef(new Auction{
        override def getAuctionSearchActor = probe.ref
      })
      auction.setState(AuctionIgnored, AuctionDataInitialized("auctionName", 0, sellerProbe.ref))
      auction ! Auction.Delete
      sellerProbe.expectMsg(Seller.Expired("auctionName"))
    }
  }
}
