package com.faforever.testharness.game.gpgnet;

import java.net.InetSocketAddress;

/**
 * Holds information on a connected peer, created from a ConnectToPeer (or JoinGame) message.
 *
 * @param ipAddress the address of the peer (in truth a local address created by the ICE adapter).
 * @param login the remote login (username) of the peer.
 * @param playerId the numeric ID of the peer. Must be unique within the match.
 */
public record Peer(InetSocketAddress ipAddress, String login, int playerId) {

    /** Default constructor. */
    public Peer {}

    /**
     * Constructor that automatically parses a string version of the IP address.
     *
     * @param ipAddress the address of the peer (in truth a local address created by the ICE
     *     adapter).
     * @param login the remote login (username) of the peer.
     * @param playerId the numeric ID of the peer. Must be unique within the match.
     * @throws IllegalArgumentException if the ipAddress is invalid.
     */
    public Peer(String ipAddress, String login, int playerId) {
        this(parseAddress(ipAddress), login, playerId);
    }

    /* Turn a string in the form host:port into an InetSocketAddress. */
    private static InetSocketAddress parseAddress(final String ipAddress) {
        int colon = ipAddress.lastIndexOf(':');
        if (colon <= 0 || colon == ipAddress.length() - 1) {
            throw new IllegalArgumentException("not a host:port address: " + ipAddress);
        }
        String host = ipAddress.substring(0, colon);
        final int port;
        try {
            port = Integer.parseInt(ipAddress.substring(colon + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a host:port address: " + ipAddress, e);
        }
        return new InetSocketAddress(host, port);
    }
}
