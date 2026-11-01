package legacy.fixture;

import io.ominicat.core.Frame;
import io.ominicat.core.FrameSink;
import io.ominicat.core.Processor;
import io.ominicat.core.spi.ProcessorFactory;
import java.util.Map;

/** Compiled once against the frozen pre-description Java 17 SPI. */
public final class LegacyFactory implements ProcessorFactory {
    @Override public String type() { return "legacy-fixture"; }

    @Override public Processor create(Map<String, String> options) {
        String prefix = options.getOrDefault("prefix", "legacy");
        return new Processor() {
            private int sequence;
            private boolean closed;
            @Override public void process(Frame frame, FrameSink next) throws Exception {
                if (closed) throw new IllegalStateException("legacy processor is closed");
                if (frame instanceof Frame.Text text) {
                    next.accept(new Frame.Text(prefix + ":" + ++sequence + ":" + text.text()));
                } else next.accept(frame);
            }
            @Override public void close() { closed = true; }
        };
    }
}
