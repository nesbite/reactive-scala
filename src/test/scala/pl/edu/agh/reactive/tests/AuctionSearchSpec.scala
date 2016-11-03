package pl.edu.agh.reactive.tests

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import org.scalatest.{BeforeAndAfterAll, WordSpecLike}

class AuctionSearchSpec extends TestKit(ActorSystem("AuctionSearchSpec"))
  with WordSpecLike with BeforeAndAfterAll with ImplicitSender {

  override def afterAll(): Unit = {
    system.terminate
  }

  "An AuctionSearch" must {
    ""
  }
}
