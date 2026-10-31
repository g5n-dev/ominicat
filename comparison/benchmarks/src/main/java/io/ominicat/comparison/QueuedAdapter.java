package io.ominicat.comparison;

import io.ominicat.comparison.NumericWorkload.Value;

/** One outstanding request; completion includes a separately validated terminal output. */
interface QueuedAdapter extends AutoCloseable {
    Value invoke(Value input) throws Exception;
    void assertDrained() throws Exception;
    String version();
    @Override void close() throws Exception;

    static QueuedAdapter create(String engine, int stages, NumericWorkload.Terminal terminal) throws Exception {
        return switch (engine) {
            case "ominicat" -> new OminiCatAdapter(stages, terminal);
            case "camel" -> new CamelAdapter(stages, terminal);
            default -> throw new IllegalArgumentException("unsupported engine");
        };
    }
}
