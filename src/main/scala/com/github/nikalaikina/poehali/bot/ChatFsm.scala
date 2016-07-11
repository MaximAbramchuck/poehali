package com.github.nikalaikina.poehali.bot

import java.time.LocalDate

import akka.actor.{ActorRef, FSM}
import akka.pattern.AskSupport
import akka.util.Timeout
import com.github.nikalaikina.poehali.api.Settings
import com.github.nikalaikina.poehali.bot.ChatFsm._
import com.github.nikalaikina.poehali.logic.{Logic, TripRoute}
import com.github.nikalaikina.poehali.mesagge.{GetRoutees, Routes}
import com.github.nikalaikina.poehali.sp.FlightsProvider

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success}

case class ChatFsm(fp: FlightsProvider, botApi: ActorRef, chatId: Long)
  extends FSM[ChatFsm.State, ChatFsm.Data] with AskSupport {

  startWith(CollectingCities, Collecting(Set("VNO"), Set()))
  botApi ! SendCityRequest(chatId, Set("VNO"))

  when(CollectingCities) {
    case Event(AddCity(city: String), chat: Collecting) =>
      botApi ! SendCityRequest(chatId, chat.homeCities | chat.cities + city)
      goto(CollectingCities) using Collecting(chat.homeCities, chat.cities + city)

    case Event(Calculate, chat: Collecting) =>
      if (chat.cities.isEmpty) {
        botApi ! SendTextAnswer(chatId, "Add at least one city.")
        goto(CollectingCities) using chat
      } else {
        calc(chat).onComplete {
          case Success(list: List[TripRoute]) =>
            val result = ResultRoutes(chat.homeCities, list)
            self ! Calculated(result)
          case Failure(e) => println(s"failed: ${e.getMessage}")
        }
        botApi ! SendTextAnswer(chatId, "I'll answer you as soon as I have the results.")
        goto(Calculating) using chat
      }
  }

  when(Calculating) {
    case Event(Calculated(result), _) =>
      botApi ! SendBestRoutes(chatId, result.best)
      goto(Result) using result
  }

  when(Result) {
    case Event(GetDetails(k), result: ResultRoutes) =>
      botApi ! SendRouteDetails(chatId, result.best(k - 1))
      stay() using result
  }

  implicit val timeout = Timeout(1000 seconds)

  def calc(chat: Collecting): Future[List[TripRoute]] = {
    val settings = Settings(chat.homeCities, chat.homeCities ++ chat.cities, LocalDate.now(), LocalDate.now().plusMonths(8), 4, 30, 1000, Math.min(2, chat.cities.size - 3))
    (Logic.logic(settings, fp) ? GetRoutees)
      .mapTo[Routes]
      .map(_.routes)
  }

}

object ChatFsm {
  sealed trait State
  case object Idle extends State
  case object CollectingCities extends State
  case object Result extends State
  case object Calculating extends State

  sealed trait Data
  case class Collecting(homeCities: Set[String], cities: Set[String]) extends Data
  case class ResultRoutes(homeCities: Set[String], routes: List[TripRoute]) extends Data {
    val best: List[TripRoute] = {
      val byCitiesCount = routes.groupBy(_.citiesCount(homeCities))
      byCitiesCount.keys.toList.sortBy(-_).take(3).map(n => {
        byCitiesCount(n)
          .groupBy(_.cities(homeCities))
          .values
          .map(routes => routes.minBy(_.cost))
      }).flatMap(_.toList)
    }
  }
}

sealed trait ChatMessage
case class AddCity(cityId: String) extends ChatMessage
case object Calculate extends ChatMessage
case class GetDetails(k: Int) extends ChatMessage
case class Calculated(resultRoutes: ResultRoutes) extends ChatMessage