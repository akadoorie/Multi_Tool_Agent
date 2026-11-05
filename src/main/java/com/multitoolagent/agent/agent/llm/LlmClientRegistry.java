package com.multitoolagent.agent.agent.llm;

import com.multitoolagent.agent.config.AppProperties;
import com.multitoolagent.agent.exception.InvalidRequestException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Picks which {@link ILlmClient} runs a task. Every client bean registers itself under its
 * {@link ILlmClient#id()} (the same self-registration pattern as {@code ToolRegistry}), so supporting a
 * new framework needs no change here.
 *
 * <p>A request may name a client; when it doesn't, {@code app.agent.default-engine} is used.
 */
@Component
public class LlmClientRegistry {

    private final Map<String, ILlmClient> clients = new TreeMap<>();
    private final String defaultId;

    public LlmClientRegistry(List<ILlmClient> clients, AppProperties props) {
        for (ILlmClient client : clients) {
            if (this.clients.putIfAbsent(client.id(), client) != null) {
                throw new IllegalStateException("Two LLM clients share the id '" + client.id() + "'.");
            }
        }
        this.defaultId = props.agent().defaultEngine();
        // Fail at startup, not on the first request, if the configured default doesn't exist.
        if (!this.clients.containsKey(defaultId)) {
            throw new IllegalStateException("app.agent.default-engine '" + defaultId
                    + "' is not one of the available engines " + ids() + ".");
        }
    }

    /**
     * Returns the client to use for a task.
     *
     * @param requestedId the engine the caller asked for; null/blank means "use the default".
     * @throws InvalidRequestException (400) if the id is not a known engine.
     */
    public ILlmClient resolve(String requestedId) {
        String id = (requestedId == null || requestedId.isBlank()) ? defaultId : requestedId.strip();
        ILlmClient client = clients.get(id);
        if (client == null) {
            throw new InvalidRequestException(
                    "Unknown engine '" + id + "'. Valid engines: " + ids() + ".");
        }
        return client;
    }

    /** The default client (used when a request names none). */
    public ILlmClient defaultClient() {
        return clients.get(defaultId);
    }

    /** Every available client, ordered by id. */
    public List<ILlmClient> all() {
        return List.copyOf(clients.values());
    }

    /** The available engine ids, sorted (for error messages and docs). */
    public List<String> ids() {
        return List.copyOf(clients.keySet());
    }
}
