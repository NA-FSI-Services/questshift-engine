package io.questshift.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

@ApplicationScoped
public class StateSerializer {

    private final ObjectMapper json = configure(new ObjectMapper());
    private final ObjectMapper yaml = configure(new ObjectMapper(
            new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)));

    private static ObjectMapper configure(ObjectMapper mapper) {
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.findAndRegisterModules();
        return mapper;
    }

    public String toJson(GameSession session) {
        try {
            session.tickElapsed();
            return json.writerWithDefaultPrettyPrinter().writeValueAsString(session);
        } catch (IOException e) {
            throw new IllegalStateException("JSON export failed", e);
        }
    }

    public String toYaml(GameSession session) {
        try {
            session.tickElapsed();
            return yaml.writeValueAsString(session);
        } catch (IOException e) {
            throw new IllegalStateException("YAML export failed", e);
        }
    }

    public void writeJson(GameSession session, OutputStream out) throws IOException {
        session.tickElapsed();
        json.writerWithDefaultPrettyPrinter().writeValue(out, session);
    }

    public void writeYaml(GameSession session, OutputStream out) throws IOException {
        session.tickElapsed();
        yaml.writeValue(out, session);
    }

    public GameSession fromJson(String body) {
        try {
            return json.readValue(body, GameSession.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid JSON session", e);
        }
    }

    public GameSession fromYaml(String body) {
        try {
            return yaml.readValue(body, GameSession.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid YAML session", e);
        }
    }

    public GameSession fromJson(InputStream in) throws IOException {
        return json.readValue(in, GameSession.class);
    }

    public GameSession fromYaml(InputStream in) throws IOException {
        return yaml.readValue(in, GameSession.class);
    }

    public GameSession from(String body, String format) {
        if (format != null && format.toLowerCase().contains("yaml")) {
            return fromYaml(body);
        }
        if (body != null && (body.startsWith("---") || looksLikeYaml(body))) {
            return fromYaml(body);
        }
        return fromJson(body);
    }

    private static boolean looksLikeYaml(String body) {
        String trimmed = body.stripLeading();
        return trimmed.startsWith("id:") || trimmed.startsWith("campaignId:");
    }
}
