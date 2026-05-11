package com.bydmate.app.data.remote

/**
 * Iternio asked us to slow down. Surfaced when the upstream returns HTTP 429.
 *
 * @param retryAfterSec value parsed from the `Retry-After` header in seconds.
 *                      Null when the header was missing or malformed — caller
 *                      should fall back to its own backoff policy.
 */
class IternioRateLimitException(val retryAfterSec: Int?) :
    RuntimeException("Iternio HTTP 429" + (retryAfterSec?.let { ", Retry-After=${it}s" }.orEmpty()))

/**
 * Iternio (or its CDN) returned 5xx. Distinct from 4xx so the caller can apply
 * exponential backoff for transient issues without dropping the user token or
 * config.
 */
class IternioServerErrorException(val httpStatus: Int) :
    RuntimeException("Iternio HTTP $httpStatus (server-side)")
