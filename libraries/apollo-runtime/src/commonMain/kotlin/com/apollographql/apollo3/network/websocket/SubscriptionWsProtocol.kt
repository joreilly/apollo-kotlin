package com.apollographql.apollo3.network.websocket

import com.apollographql.apollo3.annotations.ApolloDeprecatedSince
import com.apollographql.apollo3.annotations.ApolloExperimental
import com.apollographql.apollo3.api.ApolloRequest
import com.apollographql.apollo3.api.Operation
import com.apollographql.apollo3.api.http.DefaultHttpRequestComposer
import com.apollographql.apollo3.api.json.jsonReader
import com.apollographql.apollo3.api.json.readAny
import com.apollographql.apollo3.network.ws.GraphQLWsProtocol
import okio.Buffer

/**
 * A [WsProtocol] for https://github.com/apollographql/subscriptions-transport-ws/blob/master/PROTOCOL.md
 *
 * Note: This protocol is no longer actively maintained, and [GraphQLWsProtocol] should be favored instead.
 */
@ApolloExperimental
@Deprecated("Migrate your server to GraphQLWsProtocol instead")
@ApolloDeprecatedSince(ApolloDeprecatedSince.Version.v4_0_0)
class SubscriptionWsProtocol(
    val connectionParams: suspend () -> Any? = { null },
) : WsProtocol {
  override val name: String
    get() = "graphql-ws"

  override suspend fun connectionInit(): ClientMessage {
    val map = mutableMapOf<String, Any?>()
    map.put("type", "connection_init")
    val params = connectionParams()
    if (params != null) {
      map.put("payload", params)
    }

    return map.toClientMessage()
  }

  override suspend fun <D : Operation.Data> operationStart(request: ApolloRequest<D>): ClientMessage {
    return mapOf(
        "id" to request.requestUuid.toString(),
        "type" to "start",
        "payload" to DefaultHttpRequestComposer.composePayload(request)
    ).toClientMessage()
  }

  override fun <D : Operation.Data> operationStop(request: ApolloRequest<D>): ClientMessage {
    return mapOf(
        "type" to "stop",
        "id" to request.requestUuid.toString(),
    ).toClientMessage()
  }

  override fun ping(): ClientMessage? {
    return null
  }

  override fun pong(): ClientMessage? {
    return null
  }

  override fun parseServerMessage(text: String): ServerMessage {
    val map = try {
      @Suppress("UNCHECKED_CAST")
      Buffer().writeUtf8(text).jsonReader().readAny() as Map<String, Any?>
    } catch (e: Exception) {
      return ParseErrorServerMessage("Cannot parse server message: '$text'")
    }

    val type = map["type"] as? String
    if (type == null) {
      return ParseErrorServerMessage("No 'type' found in server message: '$text'")
    }

    return when (type) {
      "connection_ack" -> ConnectionAckServerMessage
      "connection_error" -> ConnectionErrorServerMessage(map["payload"])
      "data", "complete", "error" -> {
        val id = map["id"] as? String
        when {
          id == null -> ParseErrorServerMessage("No 'id' found in message: '$text'")
          type == "data" -> ResponseServerMessage(id, map["payload"], false)
          type == "complete" -> CompleteServerMessage(id)
          /**
           * "error" is followed by "complete" but we send the terminal [OperationErrorServerMessage] right away
           */
          type == "error" -> OperationErrorServerMessage(id, map["payload"], true)
          else -> error("") // make the compiler happy
        }
      }

      else -> ParseErrorServerMessage("Unknown type: '$type' found in server message: '$text'")
    }
  }
}

