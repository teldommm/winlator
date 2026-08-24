package com.winlator.cmod.xserver;

import com.winlator.cmod.xserver.events.Event;

import java.io.IOException;

public class EventListener {
    public final XClient client;
    public final Bitmask eventMask;

    public EventListener(XClient client, Bitmask eventMask) {
        this.client = client;
        this.eventMask = eventMask;
    }

    public boolean isInterestedIn(int eventId) {
        return eventMask.isSet(eventId);
    }

    public boolean isInterestedIn(Bitmask mask) {
        return this.eventMask.intersects(mask);
    }

    public void sendEvent(Event event) {
        try {
            event.send(client.getSequenceNumber(), client.getOutputStream());
        }
        catch (IOException e) {
            // The write to this listener's socket failed - most likely its
            // SO_SNDTIMEO timed out because it stopped draining (e.g. wine
            // side got stuck after a different process was killed). Drop
            // just this listener's connection instead of letting it hold up
            // the caller (window destroy/map/unmap notification fan-out)
            // indefinitely, which previously froze the whole X server since
            // it runs on a single thread.
            e.printStackTrace();
            client.disconnectDueToWriteFailure();
        }
    }
}
