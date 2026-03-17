package com.skillforge.app.service;

import com.skillforge.app.model.ChatMessage;
import com.skillforge.app.repository.AttemptRepository;
import com.skillforge.app.repository.ChatMessageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Dynamic ChatService with conversation history + weak-topic personalisation.
 * Works fully without an OpenAI key via an intelligent rule-based engine.
 */
@Service
public class ChatService {

    @Autowired private ChatMessageRepository chatMessageRepository;
    @Autowired private AttemptRepository attemptRepository;
    @Autowired private TopicDistributionService topicDistributionService;
    @Autowired private OpenAIService openAIService;

    /**
     * Process a user message. Load history, build context, respond.
     * @param userId  the user ID (for personalisation)
     * @param message the user's raw message
     * @param questionContext the question currently on screen (can be null)
     */
    public String respond(Long userId, String message, Object questionContext) {
        try {
            // 1. Save user message to DB
            saveMessage(userId, "user", message);

            // 2. Load recent history
            List<ChatMessage> history = chatMessageRepository
                    .findTop10ByUserIdOrderByCreatedAtDesc(userId);
            Collections.reverse(history);

            // 3. Get weak topics for personalisation
            Map<String, Double> accuracy = topicDistributionService.getTopicAccuracy(userId);
            List<String> weakTopics = accuracy.entrySet().stream()
                    .filter(e -> e.getValue() > 0 && e.getValue() < 50)
                    .map(Map.Entry::getKey)
                    .limit(3)
                    .collect(Collectors.toList());

            // 4. Build the reply - Try OpenAI first, fallback to Rule Engine
            String reply = openAIService.getChatbotResponse(message, questionContext);
            
            // If OpenAI returns the default mock response (meaning it's not configured or failed),
            // we use our local Dynamic Rule Engine for better context-aware local replies.
            if (reply.contains("Ask me to 'explain this question'")) {
                reply = buildDynamicReply(userId, message, questionContext, weakTopics, history);
            }

            // 5. Save assistant reply to DB
            saveMessage(userId, "assistant", reply);

            return reply;
        } catch (Exception e) {
            System.err.println("[ChatService] Error processing message: " + e.getMessage());
            return "I'm having a little trouble connecting to my specialized logic right now, but I'm still here to help! \n\n" +
                   "Try asking me to **explain** the current question or give you a **hint**. " +
                   "I'll do my best to provide a helpful response!";
        }
    }

    // ─── Dynamic Rule Engine (no API key required) ────────────────────────────

    private String buildDynamicReply(Long userId, String msg, Object ctx,
                                      List<String> weakTopics, List<ChatMessage> history) {
        String m = msg.toLowerCase().trim();

        // Greetings
        if (matches(m, "hello", "hi", "hey", "good morning", "good evening")) {
            return "Hello! I'm your SkillForge Elite Tutor. I'm tracking your performance across all 17 aptitude topics. " +
                   (weakTopics.isEmpty()
                       ? "You're off to a great start! Let's dive in."
                       : "I see you could use some practice on: **" + String.join("**, **", weakTopics) + "**. Want to work on those?");
        }

        // Explain current question
        if (matches(m, "explain", "how", "solve this", "help me", "what is the answer", "tell me", "stuck")) {
            if (ctx != null) {
                return "Let me break down the current question step-by-step:\n\n" +
                       "📌 **Question Context**: " + summariseContext(ctx) + "\n\n" +
                       "🔍 **Approach**: Identify the type of problem first. " +
                       "Then apply the relevant formula. Use estimation to eliminate wrong options quickly. " +
                       "If you're stuck, try working backwards from the answer choices.\n\n" +
                       "💡 **Hint**: The explanation will be shown after you submit your answer!";
            }
            return "To explain a specific question, please navigate to the Quiz page and I'll analyse it in real-time. " +
                   "Generally, break the problem into knowns and unknowns, apply the relevant formula, and verify with substitution.";
        }

        // Hint request
        if (matches(m, "hint", "clue", "tip")) {
            if (ctx != null) {
                return "💡 **Hint**: " + summariseContext(ctx) + "\n\nLook at the key values in the question. " +
                       "Think about which formula maps those values to an answer. " +
                       "Eliminate options that are clearly too large or too small.";
            }
            return "💡 For any aptitude problem: (1) Read twice, (2) Identify what's asked, (3) Circle the key numbers, (4) Pick the right formula, (5) Calculate, (6) Verify with estimation.";
        }

        // Weak topics / performance
        if (matches(m, "weak", "performance", "progress", "where am i", "my topics", "struggling")) {
            if (weakTopics.isEmpty()) {
                return "🌟 Great job! You're performing above 50% across all topics attempted so far. " +
                       "Keep pushing to master the harder levels!";
            }
            return "📊 Based on your recent performance, your weak areas are:\n" +
                   weakTopics.stream().map(t -> "• **" + t + "**").collect(Collectors.joining("\n")) +
                   "\n\nThe platform will automatically generate more questions in these areas. " +
                   "Want a quick strategy tip for any of these topics?";
        }

        // Strategy tips
        if (matches(m, "strategy", "shortcut", "trick", "faster", "speed")) {
            return "⚡ **Top Aptitude Shortcuts**:\n" +
                   "1. **Percentage**: x% of y = y% of x (commutative trick)\n" +
                   "2. **Profit & Loss**: Always find CP first using SP × 100/(100±P%)\n" +
                   "3. **Time & Work**: Convert to work rates (1/days) and add\n" +
                   "4. **Series**: Check ×2, +n, −n, and Fibonacci patterns first\n" +
                   "5. **Probability**: P(A∪B) = P(A)+P(B)−P(A∩B) is the key formula\n" +
                   "6. **Directions**: Always draw a diagram — never solve mentally";
        }

        // Topic-specific help
        for (String topic : TopicDistributionService.ALL_TOPICS) {
            if (m.contains(topic.toLowerCase())) {
                return getTopicStrategy(topic);
            }
        }

        // Motivation
        if (matches(m, "tired", "bored", "give up", "hard", "difficult")) {
            return "I understand — aptitude training is challenging. But remember: every expert was once a beginner. " +
                   "The platform is adapting the difficulty to your level, so each question is designed to stretch you just enough. " +
                   "Take a short break and come back refreshed. You've got this! 💪";
        }

        // Thanks
        if (matches(m, "thank", "thanks", "great", "awesome")) {
            return "You're very welcome! Your dedication will pay off. Shall we continue practising?";
        }

        // Default — context-aware fallback
        return "I'm here to help you master aptitude! You can ask me to:\n" +
               "• **explain** the current question\n" +
               "• **give me a hint**\n" +
               "• **show my weak topics**\n" +
               "• **tips for [topic name]**\n" +
               "• **solving shortcuts**\n\n" +
               "What would you like help with?";
    }

    private String getTopicStrategy(String topic) {
        switch (topic) {
            case "Percentage": return "📘 **Percentage Tips**: Remember, x% of y always equals y% of x. For successive changes, use the formula: Net% = a+b+ab/100. Always work with 100 as the base.";
            case "Profit and Loss": return "📘 **P&L Tips**: CP is your anchor. SP = CP×(100+profit%)/100. For mark-up and discount: final SP = MP×(1−d/100). Never add percentages directly.";
            case "Time and Work": return "📘 **Time & Work Tips**: Convert to daily work rates. If A does in 'a' days, rate = 1/a. Together = 1/a + 1/b. LCM method is fastest for integer results.";
            case "Time Speed Distance": return "📘 **TSD Tips**: Relative speed = sum (opposite), difference (same direction). Average speed = 2ab/(a+b) for equal distances. D=S×T always.";
            case "Probability": return "📘 **Probability Tips**: P(A∪B) = P(A)+P(B)−P(A∩B). For 'at least one' problems: 1−P(none). Use combinations for 'choose' problems.";
            case "Logical Series": return "📘 **Series Tips**: Check arithmetic (+n), geometric (×n), and alternating patterns. Also try: differences between consecutive terms to spot hidden patterns.";
            case "Blood Relations": return "📘 **Blood Relations Tips**: Always draw a family tree diagram. Map each clue as an arrow. 'X is Z's mother's brother' → draw step by step.";
            default: return "📘 **" + topic + " Tips**: Break the problem into what's given and what's asked. Match values to the primary formula for this topic. Verify by back-substitution.";
        }
    }

    private boolean matches(String message, String... keywords) {
        for (String kw : keywords) if (message.contains(kw)) return true;
        return false;
    }

    private String summariseContext(Object ctx) {
        if (ctx == null) return "no active question";
        String raw = ctx.toString();
        // Extract text field if it's a map
        if (raw.contains("text=") || raw.contains("\"text\"")) {
            int s = raw.indexOf("text=") >= 0 ? raw.indexOf("text=") + 5 : raw.indexOf("\"text\"") + 8;
            int e = raw.indexOf(",", s);
            if (e < 0) e = raw.indexOf("}", s);
            if (e > s) return raw.substring(s, Math.min(e, s + 200));
        }
        return raw.substring(0, Math.min(raw.length(), 200));
    }

    private void saveMessage(Long userId, String role, String content) {
        ChatMessage msg = new ChatMessage();
        msg.setUserId(userId);
        msg.setRole(role);
        msg.setContent(content);
        chatMessageRepository.save(msg);
    }
}
