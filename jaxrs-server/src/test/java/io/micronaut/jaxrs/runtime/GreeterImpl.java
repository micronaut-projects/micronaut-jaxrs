package io.micronaut.jaxrs.runtime;

import jakarta.inject.Singleton;

@Singleton
public class GreeterImpl implements GreeterService {
    @Override
    public String greet() {
        return "hello!!!";
    }
}
