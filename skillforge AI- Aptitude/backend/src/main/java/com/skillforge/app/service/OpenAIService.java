package com.skillforge.app.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.app.model.Question;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class OpenAIService {

    @Value("${openai.api.key}")
    private String openAiApiKey;

    @Autowired
    private RandomizedQuestionEngine randomizedQuestionEngine;

    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";

    private boolean hasApiKey() {
        return openAiApiKey != null
            && !openAiApiKey.isBlank()
            && !openAiApiKey.equals("YOUR_OPENAI_API_KEY_HERE");
    }

    // ─── Question Generation ──────────────────────────────────────────────────

    /**
     * Generate a single hard, unique question for the given topic and level.
     * Uses OpenAI if the key is configured, otherwise falls back to the engine.
     *
     * @param topic            one of the 17 aptitude topics
     * @param level            L1 / L2 / L3 / L4
     * @param seenQuestionTexts list of question texts already shown to this user (to avoid repeats)
     */
    public Question generateQuestion(String topic, String level, List<String> seenQuestionTexts) {
        if (!hasApiKey()) {
            return randomizedQuestionEngine.generate(topic, level);
        }
        return generateViaOpenAI(topic, level, seenQuestionTexts);
    }

    private Question generateViaOpenAI(String topic, String level, List<String> seen) {
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(openAiApiKey);

        String seenContext = seen.isEmpty() ? ""
            : "The user has ALREADY seen these question types:\n"
              + String.join("\n", seen.subList(Math.max(0, seen.size()-10), seen.size()))
              + "\nDo NOT generate a question of the same type or with the same reasoning pattern.";

        String hardnessContext = getHardnessContext(level);

        String prompt = "You are an elite aptitude exam question designer for top competitive exams (CAT, GMAT, GRE).\n"
            + "Topic: **" + topic + "**\n"
            + "Difficulty: " + hardnessContext + "\n"
            + seenContext + "\n\n"
            + "Requirements:\n"
            + "- Question must be genuinely hard, multi-step, and conceptually different from basic value-changed variants.\n"
            + "- The reasoning pattern must be UNIQUE — not just different numbers.\n"
            + "- All 4 options must be plausible (not obviously wrong).\n"
            + "- Include a complete step-by-step explanation with formula references.\n"
            + "- Respond ONLY with a raw JSON object (no markdown fences, no extra text).\n"
            + "Schema:\n"
            + "{\n"
            + "  \"topic\": \"" + topic + "\",\n"
            + "  \"questionText\": \"...\",\n"
            + "  \"optionA\": \"...\",\n"
            + "  \"optionB\": \"...\",\n"
            + "  \"optionC\": \"...\",\n"
            + "  \"optionD\": \"...\",\n"
            + "  \"correctOption\": \"A|B|C|D\",\n"
            + "  \"explanation\": \"Step-by-step solution here...\"\n"
            + "}";

        Map<String, Object> body = new HashMap<>();
        body.put("model", "gpt-4o-mini");
        body.put("messages", List.of(
            Map.of("role", "system", "content", "You are an expert aptitude exam question designer."),
            Map.of("role", "user", "content", prompt)
        ));
        body.put("temperature", 0.9);
        body.put("max_tokens", 800);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(OPENAI_API_URL, request, String.class);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(response.getBody());
            String content = root.path("choices").get(0).path("message").path("content").asText().trim();

            // Strip markdown fences if OpenAI wraps in ```json
            if (content.startsWith("```")) {
                content = content.replaceAll("```[a-z]*\\n?", "").replaceAll("```", "").trim();
            }

            JsonNode qJson = mapper.readTree(content);
            Question q = new Question();
            q.setTopic(qJson.path("topic").asText(topic));
            q.setDifficultyLevel(level);
            q.setQuestionText(qJson.path("questionText").asText());
            q.setOptionA(qJson.path("optionA").asText());
            q.setOptionB(qJson.path("optionB").asText());
            q.setOptionC(qJson.path("optionC").asText());
            q.setOptionD(qJson.path("optionD").asText());
            q.setCorrectOption(qJson.path("correctOption").asText());
            q.setExplanation(qJson.path("explanation").asText());
            return q;
        } catch (Exception e) {
            System.err.println("[OpenAI] Question generation failed for topic=" + topic + ": " + e.getMessage());
            return randomizedQuestionEngine.generate(topic, level);
        }
    }

    private String getHardnessContext(String level) {
        switch (level) {
            case "L1": return "L1 — Basic formula application. Single-step calculation. Accessible to beginners.";
            case "L2": return "L2 — Two-step reasoning. Requires careful reading and moderate formula usage. Moderate exam level.";
            case "L3": return "L3 — Multi-step analytical problem. Combines 2+ concepts. Equivalent to CAT/GMAT difficulty.";
            case "L4": return "L4 — Expert level. Complex multi-layered problem, ambiguous options, requires deep insight. Hardest competitive exam standard.";
            default:   return "Standard aptitude difficulty.";
        }
    }

    // ─── Chatbot ──────────────────────────────────────────────────────────────

    public String getChatbotResponse(String userMessage, Object context) {
        if (!hasApiKey()) {
            return getProfessionalMockResponse(userMessage, context);
        }

        String contextStr = context != null
            ? " The user is currently solving: " + context.toString()
            : "";

        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(openAiApiKey);

        String systemPrompt = "You are SkillForge AI, an elite aptitude coach for competitive exams (CAT, GMAT, GRE). "
            + "Give specific, step-by-step explanations. Be concise but thorough. Do not use filler phrases."
            + contextStr;

        Map<String, Object> body = new HashMap<>();
        body.put("model", "gpt-4o-mini");
        body.put("messages", List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user", "content", userMessage)
        ));

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> resp = restTemplate.postForEntity(OPENAI_API_URL, request, String.class);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(resp.getBody());
            return root.path("choices").get(0).path("message").path("content").asText();
        } catch (Exception e) {
            return getProfessionalMockResponse(userMessage, context);
        }
    }

    private String getProfessionalMockResponse(String userMessage, Object context) {
        String msg = userMessage.toLowerCase();
        if (context != null && (msg.contains("solve") || msg.contains("answer") || msg.contains("how") || msg.contains("explain"))) {
            return "Looking at the current problem: identify what's given, what's asked, then apply the relevant formula. "
                + "Check the explanation shown after submitting for a full step-by-step breakdown!";
        }
        if (msg.contains("hello") || msg.contains("hi")) {
            return "Hello! I'm your SkillForge Elite Tutor. Ask me to explain any question or give you solving shortcuts.";
        }
        if (msg.contains("thank")) {
            return "You're welcome! Consistency is key — keep practising!";
        }
        return "Ask me to 'explain this question', 'give me a hint', or 'show my weak topics' — I'm here to help!";
    }
}
