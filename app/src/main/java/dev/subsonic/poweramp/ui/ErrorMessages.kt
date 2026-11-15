package dev.subsonic.poweramp.ui

import dev.subsonic.poweramp.server.ServerException
import dev.subsonic.poweramp.server.UnexpectedResponseException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Turns a connection-test failure into something a user can act on. */
fun describeConnectionError(e: Throwable): String = when (e) {
    is ServerException -> when (e.kind) {
        ServerException.Kind.WRONG_CREDENTIALS -> "Wrong username or password."
        ServerException.Kind.LOGIN_METHOD_NOT_SUPPORTED -> "This server doesn't support this login method. Try the other one."
        ServerException.Kind.INVALID_API_KEY -> "The server rejected this API key. Check it, or create a new one."
        ServerException.Kind.NOT_AUTHORIZED -> "This account isn't allowed to use the API."
        ServerException.Kind.NOT_FOUND -> "The server couldn't find that item."
        ServerException.Kind.VERSION_MISMATCH -> "This app and the server use incompatible API versions."
        ServerException.Kind.OTHER -> "Server error: ${e.serverMessage ?: "no details"}"
    }.let { generic -> e.hint ?: generic } + (e.helpUrl?.let { "\nHelp: $it" } ?: "")
    is UnexpectedResponseException -> e.message ?: "Unexpected response from server."
    is UnknownHostException -> "Can't find that server. Check the address."
    is ConnectException -> "Couldn't connect. Is the server running, and is the port right?"
    is SocketTimeoutException -> "The server took too long to answer."
    is SSLException ->
        "Secure connection failed. If the server uses a self-signed certificate, install it on the device first."
    is IllegalArgumentException -> e.message ?: "Invalid input."
    else -> e.message ?: e.javaClass.simpleName
}
