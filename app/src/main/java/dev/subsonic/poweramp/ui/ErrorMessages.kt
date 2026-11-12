package dev.subsonic.poweramp.ui

import dev.subsonic.poweramp.subsonic.SubsonicApiException
import dev.subsonic.poweramp.subsonic.SubsonicApiException.Companion as Code
import dev.subsonic.poweramp.subsonic.UnexpectedResponseException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Turns a connection-test failure into something a user can act on. */
fun describeConnectionError(e: Throwable): String = when (e) {
    is SubsonicApiException -> when (e.code) {
        Code.WRONG_CREDENTIALS -> "Wrong username or password."
        Code.TOKEN_AUTH_NOT_SUPPORTED ->
            "This server doesn't accept password logins from apps. Use an API key instead."
        Code.AUTH_MECHANISM_NOT_SUPPORTED ->
            "This server doesn't support this login method. Try the other one."
        Code.INVALID_API_KEY -> "The server rejected this API key. Check it, or create a new one."
        Code.CONFLICTING_AUTH -> "The server received conflicting login details (app bug)."
        Code.NOT_AUTHORIZED -> "This account isn't allowed to use the API."
        Code.CLIENT_TOO_OLD -> "The server needs a newer Subsonic API version than this app supports."
        Code.SERVER_TOO_OLD -> "The server is too old for this app."
        else -> "Server error ${e.code}: ${e.serverMessage ?: "no details"}"
    } + (e.helpUrl?.let { "\nHelp: $it" } ?: "")
    is UnexpectedResponseException -> e.message ?: "Unexpected response from server."
    is UnknownHostException -> "Can't find that server. Check the address."
    is ConnectException -> "Couldn't connect. Is the server running, and is the port right?"
    is SocketTimeoutException -> "The server took too long to answer."
    is SSLException ->
        "Secure connection failed. If the server uses a self-signed certificate, install it on the device first."
    is IllegalArgumentException -> e.message ?: "Invalid input."
    else -> e.message ?: e.javaClass.simpleName
}
