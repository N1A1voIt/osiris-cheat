package com.example.orisischeat

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.firebase.models.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.FirebaseAI
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.fold
import org.json.JSONArray
import org.json.JSONObject

/** One extracted multiple-choice question and its possible answers. */
data class ExtractedQuestion(
    val question: String,
    val answers: List<String>,
) {
    fun toJson(): JSONObject =
        JSONObject().put("question", question).put("answers", JSONArray(answers))

    companion object {
        /** Parses the model's structured JSON response into a [ExtractedQuestion]. */
        fun fromJson(json: String): ExtractedQuestion {
            val obj = JSONObject(json)
            val question = obj.getString("question")
            val answers = obj.optJSONArray("answers") ?: JSONArray()
            return ExtractedQuestion(
                question = question,
                answers = (0 until answers.length()).map { answers.getString(it) },
            )
        }
    }
}

/**
 * ADK agent that reads a picture of a multiple-choice question (screenshot or
 * camera photo) and returns it as structured JSON:
 * {"question": "...", "answers": ["...", ...]}.
 *
 * The agent is built with ADK for Kotlin/Android (https://adk.dev):
 * - [LlmAgent] with a cloud Gemini model,
 * - an [outputSchema] (ADK `Schema`, OpenAPI subset) forcing the model to emit
 *   a top-level object with `question` (string) and `answers` (array of strings),
 * - ADK validates the final response against the schema before we parse it.
 */
object QuestionExtractor {

    private const val INSTRUCTION =
        "You read pictures of multiple-choice questions. " +
            "Extract the question text exactly as written and every answer choice " +
            "shown, keeping their original wording and order. " +
            "Do not answer the question and do not add anything else."

    /** Output contract: {"question": string, "answers": string[]}. */
    private val questionSchema = Schema(
        type = Type.OBJECT,
        title = "ExtractedQuestion",
        properties = mapOf(
            "question" to Schema(
                type = Type.STRING,
                description = "The question text, verbatim.",
            ),
            "answers" to Schema(
                type = Type.ARRAY,
                description = "Every answer choice shown, in order, verbatim.",
                items = Schema(type = Type.STRING),
            ),
        ),
        required = listOf("question", "answers"),
    )

    /**
     * Cloud Gemini via **Firebase AI Logic** - the supported way to call
     * Gemini from Android. The raw GenAI SDK blocks API-key/credential
     * initialization on Android ("Initializing the Client with an API Key or
     * Credentials is blocked on Android"), and ADK ships a dedicated Firebase
     * model for exactly this reason.
     *
     * One-time setup (see local.properties):
     * 1. Create a Firebase project -> console.firebase.google.com
     * 2. Add an Android app with our package name (com.example.orisischeat)
     * 3. Enable Firebase AI Logic (build with Gemini API)
     * 4. Copy apiKey / appId / projectId from google-services.json into
     *    local.properties (firebaseApiKey / firebaseAppId / firebaseProjectId)
     *
     * Those three values are project identifiers, not secrets - they are
     * public by design and always ship inside the APK.
     */
    private const val FIREBASE_MODEL = "gemini-flash-latest"

    private var initialized = false

    /** Must be called once (e.g. in Activity.onCreate) before [extract]. */
    fun init(context: android.content.Context) {
        if (initialized) return
        initialized = true

        if (BuildConfig.FIREBASE_APP_ID.isNotBlank() && FirebaseApp.getApps(context).isEmpty()) {
            val options = com.google.firebase.FirebaseOptions.Builder()
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .build()
            FirebaseApp.initializeApp(context, options)
        }
        // If FIREBASE_APP_ID is blank, extract() throws a clear message below.
    }

    private fun buildModel(): Firebase {
        check(initialized) { "QuestionExtractor.init(context) was not called" }
        return Firebase.create(
            FIREBASE_MODEL,
            FirebaseAI.getInstance(FirebaseApp.getInstance()),
        )
    }

    val agent: LlmAgent by lazy {
        LlmAgent(
            name = "question_extractor",
            description = "Extracts a multiple-choice question from a picture as JSON.",
            model = buildModel(),
            instruction = Instruction(INSTRUCTION),
            outputSchema = questionSchema,
        )
    }

    /**
     * Sends the image bytes to the agent and returns the parsed result.
     * @param imageBytes raw PNG/JPEG bytes of the screenshot or photo
     * @param mimeType e.g. "image/png" or "image/jpeg"
     */
    suspend fun extract(imageBytes: ByteArray, mimeType: String = "image/png"): ExtractedQuestion =
        withContext(Dispatchers.IO) {
            val sessionService = com.google.adk.kt.sessions.InMemorySessionService()
            val runner = com.google.adk.kt.runners.InMemoryRunner(agent = agent)

            val session = sessionService.createSession(
                com.google.adk.kt.sessions.SessionKey(
                    appName = runner.appName,
                    userId = USER_ID,
                    id = null, // service generates a fresh session id
                ),
            )
            val sessionId = requireNotNull(session.key.id) { "session id was not generated" }

            val content = com.google.adk.kt.types.Content(
                role = com.google.adk.kt.types.Role.USER,
                parts = listOf(
                    com.google.adk.kt.types.Part(
                        text = "Extract the question and its answer choices from this image.",
                    ),
                    com.google.adk.kt.types.Part(
                        inlineData = com.google.adk.kt.types.Blob(
                            mimeType = mimeType,
                            displayName = "capture",
                            data = imageBytes,
                        ),
                    ),
                ),
            )

            // Collect the stream; the last event carrying text is the final
            // structured response validated against outputSchema by ADK.
            runner.runAsync(
                userId = USER_ID,
                sessionId = sessionId,
                newMessage = content,
            ).fold(initial = "") { acc, event ->
                val text = event.content?.parts?.joinToString(separator = "") { it.text ?: "" }
                if (text.isNullOrBlank()) acc else text
            }.let { finalText ->
                if (finalText.isBlank()) {
                    throw IllegalStateException("Agent returned an empty response")
                }
                ExtractedQuestion.fromJson(finalText)
            }
        }

    private const val USER_ID = "orisis-user"
}
