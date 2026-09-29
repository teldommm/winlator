package com.winlator.cmod.components;

/** What to tell the user before removing a component: either "can't, because..." or "sure?". */
public final class RemovePlan {
    public final boolean blocked;
    public final String title;
    public final String message;

    private RemovePlan(boolean blocked, String title, String message) {
        this.blocked = blocked;
        this.title = title;
        this.message = message;
    }

    public static RemovePlan blocked(String title, String message) {
        return new RemovePlan(true, title, message);
    }

    public static RemovePlan confirm(String title, String message) {
        return new RemovePlan(false, title, message);
    }
}
