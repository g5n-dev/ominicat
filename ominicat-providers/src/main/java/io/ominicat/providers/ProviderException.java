package io.ominicat.providers;

/** Sanitized remote failure; never includes credentials or provider response bodies. */
public final class ProviderException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int status;
    public ProviderException(String message, int status) { super(message); this.status = status; }
    public int status() { return status; }
    public boolean retryable() { return status == 429 || status >= 500; }
}
