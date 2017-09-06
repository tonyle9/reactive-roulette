package com.github.al.roulette.player.impl

import java.util.UUID

import akka.NotUsed
import com.github.al.persistence.PersistentEntityRegistrySugar
import com.github.al.roulette.player.api
import com.github.al.roulette.player.api._
import com.lightbend.lagom.scaladsl.api.ServiceCall
import com.lightbend.lagom.scaladsl.api.broker.Topic
import com.lightbend.lagom.scaladsl.api.transport.NotFound
import com.lightbend.lagom.scaladsl.broker.TopicProducer
import com.lightbend.lagom.scaladsl.persistence.{EventStreamElement, PersistentEntityRegistry}

import scala.concurrent.{ExecutionContext, Future}

class PlayerServiceImpl(override val entityRegistry: PersistentEntityRegistry,
                        playerRepository: PlayerRepository)(implicit val executionContext: ExecutionContext)
  extends PlayerService
    with PersistentEntityRegistrySugar {

  override def registerPlayer: ServiceCall[Player, PlayerId] = ServiceCall { player =>
    val id = UUID.randomUUID()
    entityRef[PlayerEntity](id)
      .ask(CreatePlayer(PlayerState(player.playerName)))
      .map(_ => PlayerId(id))
  }

  override def login: ServiceCall[PlayerCredentials, PlayerAccessToken] = ServiceCall { credentials =>
    for {
      playerId <- playerRepository.getPlayerIdByName(credentials.playerName)
      accessToken <- entityRef[PlayerEntity](playerId).ask(IssueAccessToken)
    } yield PlayerAccessToken(accessToken)
  }

  override def getPlayer(id: UUID): ServiceCall[NotUsed, Player] = ServiceCall { _ =>
    entityRef[PlayerEntity](id).ask(GetPlayer).map {
      case Some(playerState) => Player(playerState.playerName)
      case None => throw NotFound(s"Player $id not found")
    }
  }

  override def playerEvents: Topic[api.PlayerEvent] = TopicProducer.singleStreamWithOffset { offset =>
    entityRegistry.eventStream(PlayerEvent.Tag, offset)
      .mapAsync(1)({
        case EventStreamElement(playerId, PlayerCreated(_), _offset) =>
          Future.successful(api.PlayerRegistered(playerId) -> _offset)
      })
  }
}