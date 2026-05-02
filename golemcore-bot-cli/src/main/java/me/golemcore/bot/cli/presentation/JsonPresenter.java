package me.golemcore.bot.cli.presentation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.util.Objects;

public final class JsonPresenter {

    private final ObjectMapper objectMapper;
    private final RedactionPolicy redactionPolicy;

    public JsonPresenter() {
        this(new ObjectMapper(), RedactionPolicy.defaultPolicy());
    }

    JsonPresenter(ObjectMapper objectMapper) {
        this(objectMapper, RedactionPolicy.defaultPolicy());
    }

    JsonPresenter(ObjectMapper objectMapper, RedactionPolicy redactionPolicy) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").findAndRegisterModules();
        this.redactionPolicy = Objects.requireNonNull(redactionPolicy, "redactionPolicy");
    }

    public void render(Object value, PrintWriter out) {
        try {
            JsonNode tree = objectMapper.valueToTree(value);
            out.println(objectMapper.writeValueAsString(redactionPolicy.redact(tree)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to render JSON output", exception);
        }
    }
}
