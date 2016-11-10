package pl.edu.agh.reactive.tests

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import org.scalatest.{BeforeAndAfterAll, WordSpecLike}
import pl.edu.agh.reactive.akkaFSM.{AuctionSearch, Buyer}

class AuctionSearchSpec extends TestKit(ActorSystem("AuctionSearchSpec"))
  with WordSpecLike with BeforeAndAfterAll with ImplicitSender {

  override def afterAll(): Unit = {
    system.terminate
  }

  "An AuctionSearch" must {
    "return only auctions matching keyword" in {
      val auction1 = TestProbe()
      val auction2 = TestProbe()
      val auction3 = TestProbe()
      val auctionSearch = system.actorOf(Props[AuctionSearch])
      auction1.send(auctionSearch, AuctionSearch.Register("auctionName"))
      auction2.send(auctionSearch, AuctionSearch.Register("auctionName2"))
      auction3.send(auctionSearch, AuctionSearch.Register("differentAuctionName"))
      auctionSearch ! AuctionSearch.Auctions("auction")
      expectMsg(Buyer.Auctions(List(auction1.ref, auction2.ref)))
    }

    "return an empty list if no auction matches keyword" in {
      val auction1 = TestProbe()
      val auction2 = TestProbe()
      val auctionSearch = system.actorOf(Props[AuctionSearch])
      auction1.send(auctionSearch, AuctionSearch.Register("auctionName"))
      auction2.send(auctionSearch, AuctionSearch.Register("auctionName2"))
      auctionSearch ! AuctionSearch.Auctions("differentAuctionName")
      expectMsg(Buyer.Auctions(List()))
    }

    "register an auction" in {
      val auction = TestProbe()
      val auctionSearch = TestActorRef[AuctionSearch]
      auction.send(auctionSearch, AuctionSearch.Register("auctionName"))
      auctionSearch.underlyingActor.auctions.eq(Map(auction.ref -> "auctionName"))
    }

    "not register same auction twice" in {
      val auction = TestProbe()
      val auctionSearch = TestActorRef[AuctionSearch]
      auction.send(auctionSearch, AuctionSearch.Register("auctionName"))
      auction.send(auctionSearch, AuctionSearch.Register("auctionName2"))
      auctionSearch.underlyingActor.auctions.eq(Map(auction.ref -> "auctionName"))
    }

    "unregister an auction" in {
      val auction = TestProbe()
      val auctionSearch = TestActorRef[AuctionSearch]
      auctionSearch.underlyingActor.auctions = Map(auction.ref -> "auctionName")
      auction.send(auctionSearch, AuctionSearch.Unregister)
      auctionSearch.underlyingActor.auctions.eq(Map())
    }
  }
}
