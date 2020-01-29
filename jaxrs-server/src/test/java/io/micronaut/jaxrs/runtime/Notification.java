package io.micronaut.jaxrs.runtime;

import io.micronaut.core.annotation.Introspected;

@Introspected
public class Notification {
    private final int id;
    private final String name;
    private final String message;

    public Notification(int id, String name, String message) {
        this.id = id;
        this.name = name;
        this.message = message;
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getMessage() {
        return message;
    }
}
