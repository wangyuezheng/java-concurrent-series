package com.wyz.concurrent;

public class TimeoutException extends Exception {
    private static final long serialVersionUID = 1900926677490660714L;

    /**
     * Constructs a {@code TimeoutException} with no specified detail
     * message.
     */
    public TimeoutException() {}

    /**
     * Constructs a {@code TimeoutException} with the specified detail
     * message.
     *
     * @param message the detail message
     */
    public TimeoutException(String message) {
        super(message);
    }
}
