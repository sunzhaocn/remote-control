package com.simplerc.app.signaling

import java.io.IOException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class ConnectionFailure(val code: String, val retryAfterSeconds: Int = 0) : IOException(code)

fun connectionFailure(error: Throwable, httpStatus: Int? = null, retryAfterSeconds: Int = 0): ConnectionFailure {
    if (error is ConnectionFailure) return error
    return ConnectionFailure(when {
        httpStatus == 429 -> "RATE_LIMITED"
        httpStatus == 401 -> "DEVICE_AUTH_FAILED"
        httpStatus != null -> "HTTP_$httpStatus"
        error is SSLException -> "TLS_FAILED"
        error is UnknownHostException -> "SERVER_UNREACHABLE"
        else -> "SERVER_UNREACHABLE"
    }, retryAfterSeconds)
}
