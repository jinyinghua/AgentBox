package com.shaun.agentbox.mcp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class MultiAgentState(
    val sessions: List<MultiAgentSession> = emptyList()
)

@Serializable
data class MultiAgentSession(
    val id: String,
    val title: String,
    val objective: String,
    val status: String = "active",
    val createdAt: Long,
    val updatedAt: Long,
    val agents: List<MultiAgentWorker> = emptyList(),
    val timeline: List<MultiAgentTimelineEntry> = emptyList()
)

@Serializable
data class MultiAgentWorker(
    val id: String,
    val name: String,
    val role: String,
    val task: String,
    val status: String = "idle",
    val progress: Int? = null,
    val lastMessage: String = "",
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class MultiAgentTimelineEntry(
    val id: String,
    val type: String,
    val message: String,
    val createdAt: Long,
    val agentId: String? = null,
    val agentName: String? = null,
    val supervisor: String? = null
)

class MultiAgentManager(context: Context) {

    private val appContext = context.applicationContext
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }
    private val mutex = Mutex()
    private val stateFile: File = File(appContext.filesDir, "multi_agent_state.json")

    companion object {
        private const val MAX_TIMELINE_ENTRIES = 300
    }

    suspend fun listSessions(): List<MultiAgentSession> = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadStateLocked().sessions.sortedByDescending { it.updatedAt }
        }
    }

    suspend fun getSession(sessionId: String): MultiAgentSession = withContext(Dispatchers.IO) {
        mutex.withLock {
            findSessionLocked(loadStateLocked(), sessionId)
                ?: throw IllegalArgumentException("Session not found: $sessionId")
        }
    }

    suspend fun createSession(title: String, objective: String): MultiAgentSession = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val session = MultiAgentSession(
                id = UUID.randomUUID().toString(),
                title = title,
                objective = objective,
                createdAt = now,
                updatedAt = now,
                timeline = listOf(
                    MultiAgentTimelineEntry(
                        id = UUID.randomUUID().toString(),
                        type = "session_created",
                        message = "Session created: $title",
                        createdAt = now,
                        supervisor = "orchestrator"
                    )
                )
            )
            val state = loadStateLocked()
            saveStateLocked(state.copy(sessions = listOf(session) + state.sessions))
            session
        }
    }

    suspend fun createAgent(
        sessionId: String,
        name: String,
        role: String,
        task: String
    ): MultiAgentWorker = withContext(Dispatchers.IO) {
        mutex.withLock {
            val state = loadStateLocked()
            val session = findSessionLocked(state, sessionId)
                ?: throw IllegalArgumentException("Session not found: $sessionId")

            val now = System.currentTimeMillis()
            val agent = MultiAgentWorker(
                id = UUID.randomUUID().toString(),
                name = name,
                role = role,
                task = task,
                status = "created",
                createdAt = now,
                updatedAt = now
            )
            val entry = MultiAgentTimelineEntry(
                id = UUID.randomUUID().toString(),
                type = "agent_created",
                message = "Agent $name created with task: $task",
                createdAt = now,
                agentId = agent.id,
                agentName = agent.name,
                supervisor = "orchestrator"
            )
            val updatedSession = session.copy(
                updatedAt = now,
                agents = session.agents + agent,
                timeline = appendTimeline(session.timeline, entry)
            )
            saveUpdatedSessionLocked(state, updatedSession)
            agent
        }
    }

    suspend fun updateAgentStatus(
        sessionId: String,
        agentId: String,
        status: String,
        message: String,
        progress: Int?
    ): MultiAgentWorker = withContext(Dispatchers.IO) {
        mutex.withLock {
            val state = loadStateLocked()
            val session = findSessionLocked(state, sessionId)
                ?: throw IllegalArgumentException("Session not found: $sessionId")
            val agent = session.agents.firstOrNull { it.id == agentId }
                ?: throw IllegalArgumentException("Agent not found: $agentId")

            val now = System.currentTimeMillis()
            val normalizedProgress = progress?.coerceIn(0, 100)
            val updatedAgent = agent.copy(
                status = status,
                progress = normalizedProgress,
                lastMessage = message,
                updatedAt = now
            )
            val entry = MultiAgentTimelineEntry(
                id = UUID.randomUUID().toString(),
                type = "agent_status",
                message = message,
                createdAt = now,
                agentId = agent.id,
                agentName = agent.name
            )
            val updatedSession = session.copy(
                updatedAt = now,
                agents = session.agents.map { if (it.id == agentId) updatedAgent else it },
                timeline = appendTimeline(session.timeline, entry)
            )
            saveUpdatedSessionLocked(state, updatedSession)
            updatedAgent
        }
    }

    suspend fun coordinateAgent(
        sessionId: String,
        message: String,
        agentId: String? = null,
        newTask: String? = null,
        supervisor: String = "orchestrator"
    ): MultiAgentSession = withContext(Dispatchers.IO) {
        mutex.withLock {
            val state = loadStateLocked()
            val session = findSessionLocked(state, sessionId)
                ?: throw IllegalArgumentException("Session not found: $sessionId")

            val now = System.currentTimeMillis()
            val targetAgent = agentId?.let { id ->
                session.agents.firstOrNull { it.id == id }
                    ?: throw IllegalArgumentException("Agent not found: $id")
            }

            val updatedAgents = if (targetAgent != null && newTask != null) {
                session.agents.map {
                    if (it.id == targetAgent.id) {
                        it.copy(task = newTask, updatedAt = now)
                    } else {
                        it
                    }
                }
            } else {
                session.agents
            }

            val renderedMessage = buildString {
                append(message)
                if (targetAgent != null && newTask != null) {
                    append(" | reassigned task -> ")
                    append(newTask)
                }
            }

            val entry = MultiAgentTimelineEntry(
                id = UUID.randomUUID().toString(),
                type = if (targetAgent != null) "supervisor_feedback" else "supervisor_note",
                message = renderedMessage,
                createdAt = now,
                agentId = targetAgent?.id,
                agentName = targetAgent?.name,
                supervisor = supervisor
            )

            val updatedSession = session.copy(
                updatedAt = now,
                agents = updatedAgents,
                timeline = appendTimeline(session.timeline, entry)
            )
            saveUpdatedSessionLocked(state, updatedSession)
            updatedSession
        }
    }

    suspend fun setSessionStatus(
        sessionId: String,
        status: String,
        note: String? = null,
        supervisor: String = "runtime"
    ): MultiAgentSession = withContext(Dispatchers.IO) {
        mutex.withLock {
            val state = loadStateLocked()
            val session = findSessionLocked(state, sessionId)
                ?: throw IllegalArgumentException("Session not found: $sessionId")
            val now = System.currentTimeMillis()
            val entry = note?.let {
                MultiAgentTimelineEntry(
                    id = UUID.randomUUID().toString(),
                    type = "session_status",
                    message = "$status: $it",
                    createdAt = now,
                    supervisor = supervisor
                )
            }
            val updatedSession = session.copy(
                status = status,
                updatedAt = now,
                timeline = entry?.let { appendTimeline(session.timeline, it) } ?: session.timeline
            )
            saveUpdatedSessionLocked(state, updatedSession)
            updatedSession
        }
    }

    private fun loadStateLocked(): MultiAgentState {
        if (!stateFile.exists()) return MultiAgentState()
        return runCatching {
            json.decodeFromString(MultiAgentState.serializer(), stateFile.readText())
        }.getOrElse {
            MultiAgentState()
        }
    }

    private fun saveStateLocked(state: MultiAgentState) {
        stateFile.parentFile?.mkdirs()
        stateFile.writeText(json.encodeToString(MultiAgentState.serializer(), state))
    }

    private fun saveUpdatedSessionLocked(state: MultiAgentState, updatedSession: MultiAgentSession) {
        val updatedState = state.copy(
            sessions = state.sessions.map { existing ->
                if (existing.id == updatedSession.id) updatedSession else existing
            }
        )
        saveStateLocked(updatedState)
    }

    private fun findSessionLocked(state: MultiAgentState, sessionId: String): MultiAgentSession? {
        return state.sessions.firstOrNull { it.id == sessionId }
    }

    private fun appendTimeline(
        current: List<MultiAgentTimelineEntry>,
        entry: MultiAgentTimelineEntry
    ): List<MultiAgentTimelineEntry> {
        return (current + entry).takeLast(MAX_TIMELINE_ENTRIES)
    }
}
