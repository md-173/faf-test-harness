package com.faforever.testharness.client.lobby;

/**
 * Represents an OAuth2 access token.
 *
 * @param token the textual representation of the token itself.
 * @param expiryDate when the token will expire, as a Unix timestamp (seconds since
 *     1970-01-01T00:00:00Z)
 */
public record AccessToken(String token, long expiryDate) {}
