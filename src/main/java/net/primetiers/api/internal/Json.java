package net.primetiers.api.internal;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import net.primetiers.api.model.Tier;

/** Gson set up for API responses. Not part of the public API. */
public final class Json {

    private Json() {
    }

    public static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(Instant.class, new InstantAdapter().nullSafe())
            .registerTypeAdapter(Tier.class, new TierAdapter().nullSafe())
            .disableHtmlEscaping()
            .create();

    /** Unknown tier names become null instead of failing the whole response. */
    static final class TierAdapter extends TypeAdapter<Tier> {
        @Override
        public void write(JsonWriter out, Tier value) throws IOException {
            out.value(value.name());
        }

        @Override
        public Tier read(JsonReader in) throws IOException {
            if (in.peek() != JsonToken.STRING) {
                in.skipValue();
                return null;
            }
            return Tier.parse(in.nextString()).orElse(null);
        }
    }

    /**
     * Timestamps come from a database migration, so formats vary: ISO-8601 with {@code Z}
     * or an offset, Postgres style ({@code 2025-01-01 12:00:00+00}), or no zone (read as UTC).
     * Anything unreadable becomes null.
     */
    static final class InstantAdapter extends TypeAdapter<Instant> {
        @Override
        public void write(JsonWriter out, Instant value) throws IOException {
            out.value(value.toString());
        }

        @Override
        public Instant read(JsonReader in) throws IOException {
            if (in.peek() != JsonToken.STRING) {
                in.skipValue();
                return null;
            }
            return parseInstant(in.nextString());
        }
    }

    private static final Pattern SHORT_OFFSET = Pattern.compile("([+-]\\d{2})$");

    static Instant parseInstant(String raw) {
        String s = raw.trim().replace(' ', 'T');
        s = SHORT_OFFSET.matcher(s).replaceFirst("$1:00");
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException ignored) {
            // try the next format
        }
        try {
            return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
