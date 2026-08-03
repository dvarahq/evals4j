package io.github.grabdoc.evals4j.result;

/**
 * The value an evaluator assigns. OpenEvals models this as {@code float | bool}; a score is either a
 * pass/fail verdict or a magnitude, and the distinction is meaningful — a boolean {@code false} is a
 * verdict, whereas {@code 0.0} is a measurement.
 */
public sealed interface Score {

    /** A pass/fail verdict. */
    record Bool(boolean value) implements Score {
        @Override
        public double doubleValue() {
            return value ? 1.0 : 0.0;
        }

        @Override
        public String toString() {
            return Boolean.toString(value);
        }
    }

    /** A magnitude, conventionally in {@code [0.0, 1.0]} but not constrained to it. */
    record Numeric(double value) implements Score {
        @Override
        public double doubleValue() {
            return value;
        }

        @Override
        public String toString() {
            return Double.toString(value);
        }
    }

    /** The score as a double; {@code true} is 1.0 and {@code false} is 0.0. */
    double doubleValue();

    static Score of(boolean value) {
        return new Bool(value);
    }

    static Score of(double value) {
        return new Numeric(value);
    }

    /**
     * Coerces an arbitrary value to a score. Accepts {@link Boolean}, any {@link Number}, and the
     * strings {@code "true"}/{@code "false"}.
     *
     * @throws IllegalArgumentException if the value cannot be read as a score
     */
    static Score coerce(Object value) {
        if (value instanceof Score score) {
            return score;
        }
        if (value instanceof Boolean bool) {
            return of(bool.booleanValue());
        }
        if (value instanceof Number number) {
            return of(number.doubleValue());
        }
        if (value instanceof String string) {
            if ("true".equalsIgnoreCase(string)) {
                return of(true);
            }
            if ("false".equalsIgnoreCase(string)) {
                return of(false);
            }
        }
        throw new IllegalArgumentException(
                "Cannot read a score from " + (value == null ? "null" : value.getClass().getName() + ": " + value)
                        + ". Expected a boolean or a number.");
    }
}
