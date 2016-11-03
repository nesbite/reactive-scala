package pl.edu.agh.reactive.tests

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import org.scalatest.{BeforeAndAfterAll, WordSpecLike}

class BuyerSpec extends TestKit(ActorSystem("BuyerSpec"))
  with WordSpecLike with BeforeAndAfterAll with ImplicitSender {

  override def afterAll(): Unit = {
    system.terminate
  }

  "A Buyer" must {
    ""
  }
}
