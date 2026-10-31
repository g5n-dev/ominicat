package io.ominicat.providers;

import io.ominicat.core.Processor;
import io.ominicat.core.spi.ProcessorFactory;
import java.util.Map;

/** Offline passthrough stage for transport diagnostics. */
public final class EchoFactory implements ProcessorFactory {
    @Override public String type() { return "echo"; }
    @Override public Processor create(Map<String, String> options) {
        Options.allowed(options);
        return (frame, next) -> next.accept(frame);
    }
}
