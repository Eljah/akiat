package io.github.eljah.akiat.service;

public class NotFoundException extends RuntimeException {
    public NotFoundException(String description) { super(description); }
}
