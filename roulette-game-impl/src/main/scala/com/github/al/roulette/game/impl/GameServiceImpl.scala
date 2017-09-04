package com.github.al.roulette.game.impl

import java.util.UUID

import akka.NotUsed
import akka.persistence.query.Offset
import com.github.al.persistence.PersistentEntityRegistrySugar
import com.github.al.roulette.game.api
import com.github.al.roulette.game.api.{Game, GameId, GameService}
import com.lightbend.lagom.scaladsl.api.ServiceCall
import com.lightbend.lagom.scaladsl.api.broker.Topic
import com.lightbend.lagom.scaladsl.api.transport.NotFound
import com.lightbend.lagom.scaladsl.broker.TopicProducer
import com.lightbend.lagom.scaladsl.persistence.{EventStreamElement, PersistentEntityRegistry}

import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions

class GameServiceImpl(override val entityRegistry: PersistentEntityRegistry)(implicit ec: ExecutionContext)
  extends GameService
    with PersistentEntityRegistrySugar {
  override def createGame: ServiceCall[Game, GameId] = ServiceCall { game =>
    val id = UUID.randomUUID()
    entityRefUuid[GameEntity](id)
      .ask(CreateGame(GameState(game.gameName, game.gameDuration)))
      .map(_ => GameId(id))
  }

  override def getGame(id: UUID): ServiceCall[NotUsed, Game] = ServiceCall { _ =>
    entityRefUuid[GameEntity](id).ask(GetGame).map {
      case Some(gameState) => Game(gameState.gameName, gameState.gameDuration)
      case None => throw NotFound(s"Game $id not found");
    }
  }

  override def gameEvents: Topic[api.GameEvent] = TopicProducer.singleStreamWithOffset { offset =>
    entityRegistry.eventStream(GameEvent.Tag, offset)
      .filter {
        _.event match {
          case _: GameCreated | _: GameStarted.type | _: GameFinished.type => true
          case _ => false
        }
      }.mapAsync(1)(convertGameEventsToApiEvent)
  }

  override def gameResultEvents: Topic[api.GameResulted] = TopicProducer.singleStreamWithOffset { offset =>
    entityRegistry.eventStream(GameEvent.Tag, offset)
      .filter {
        _.event match {
          case _: GameResulted => true
          case _ => false
        }
      }.mapAsync(1)(convertGameResultEventsToGameResultApiEvent)
  }

  private def convertGameEventsToApiEvent: EventStreamElement[GameEvent] => Future[(api.GameEvent, Offset)] = {
    case EventStreamElement(gameId, GameCreated(GameState(_, gameDuration, _, _, _)), offset) =>
      Future.successful(api.GameCreated(gameId, gameDuration) -> offset)
    case EventStreamElement(gameId, GameStarted, offset) =>
      Future.successful(api.GameStarted(gameId) -> offset)
    case EventStreamElement(gameId, GameFinished, offset) =>
      Future.successful(api.GameFinished(gameId) -> offset)
  }

  private def convertGameResultEventsToGameResultApiEvent: EventStreamElement[GameEvent] => Future[(api.GameResulted, Offset)] = {
    case EventStreamElement(gameId, GameResulted(winningNumber), offset) =>
      Future.successful(api.GameResulted(gameId, winningNumber) -> offset)
  }
}
