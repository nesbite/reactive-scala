package pl.edu.agh.reactive.akkaFSM

import akka.actor.{Actor, ActorNotFound, ActorPath, Props}
import akka.event.LoggingReceive

import scala.concurrent.duration._
import scala.concurrent.Await


case class Notify(auctionName: String, buyer: ActorPath, price: BigInt)

class Notifier extends Actor{
  import akka.actor.OneForOneStrategy
  import akka.actor.SupervisorStrategy._
  import scala.concurrent.duration._
  override def receive = LoggingReceive {
    case Notify(auctionName, buyer, price) =>
      val notifierRequest = context.actorOf(Props[NotifierRequest])
      notifierRequest ! Notify(auctionName, buyer, price)

  }

  override val supervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1.minute) {
      case ex: ActorNotFound     =>
        println(s"\t[Notifier] Error occurred: ${ex.getMessage}")
        Resume
      case ex: Exception                =>
        println(s"\t[Notifier] Error occurred: ${ex.getMessage}")
        Escalate
    }
}

class NotifierRequest extends Actor{
  override def receive = LoggingReceive {
    case Notify(auctionName, buyer, price) =>
      val actor = context.actorSelection("akka.tcp://AuctionPublisher@127.0.0.1:2552/user/auctionPublisher")
      Await.result(actor.resolveOne(2.seconds), 2.seconds)
      actor ! Notify(auctionName, buyer, price)
  }
}

