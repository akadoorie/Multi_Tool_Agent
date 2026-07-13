package com.tufin.agent.repository;

import com.tufin.agent.model.ConversationSummary;
import com.tufin.agent.model.TaskRecord;
import java.util.List;
import java.util.Optional;

/**
 * Persistence abstraction for tasks. Depending on this interface (rather than a concrete file store)
 * is the seam that lets a database implementation be dropped in later without touching the service.
 *
 * <p>The conversation methods are deliberately <em>query-shaped</em> &mdash; each is something a DB
 * could serve with an index on {@code conversation_id}/{@code conversation_order}, so a SQLite/Postgres
 * implementation materialises only the rows asked for, never the whole store.
 */
public interface ITaskRepository {

    /**
     * Persists a completed task, assigning its {@code conversationOrder} as the next position in its
     * conversation, and returns the stored record (with that order). The assignment happens atomically
     * with the append so two turns in one conversation can't collide.
     */
    TaskRecord save(TaskRecord record);

    /** Looks up a task by id. */
    Optional<TaskRecord> findById(String taskId);

    /** Returns all stored tasks. */
    List<TaskRecord> findAll();

    /** Number of turns in a conversation (0 for an unknown id). */
    int countByConversation(String conversationId);

    /**
     * The most-recent {@code limit} turns of a conversation, ordered by {@code conversationOrder}
     * ascending (oldest first) &mdash; the capped context for a follow-up. Never loads the whole
     * conversation.
     */
    List<TaskRecord> findByConversation(String conversationId, int limit);

    /** One page of a conversation's turns, ordered by {@code conversationOrder} ascending. */
    List<TaskRecord> findConversationPage(String conversationId, int page, int size);

    /**
     * One page of conversation summaries, newest first by last activity (tie-broken by id), each a
     * lightweight {@link ConversationSummary}. A DB would serve this with
     * {@code ORDER BY last_activity_at DESC LIMIT ? OFFSET ?}.
     */
    List<ConversationSummary> findConversationsPage(int page, int size);

    /** Total number of conversations (for {@code total_elements} / {@code total_pages}). */
    int countConversations();
}
