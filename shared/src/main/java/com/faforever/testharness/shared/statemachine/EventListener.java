package com.faforever.testharness.shared.statemachine;

/** A general interface for any object that listens for events. */
public interface EventListener {
    /**
     * Receive an event from a subscribed source.
     *
     * @param event the event received.
     */
    void receiveEvent(Event event);
}
