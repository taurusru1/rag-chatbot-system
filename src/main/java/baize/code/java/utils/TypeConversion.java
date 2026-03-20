package baize.code.java.utils;

import baize.code.java.entity.SessionLog;
import org.springframework.ai.chat.messages.*;

import static baize.code.java.entity.SessionLog.Type.*;

public class TypeConversion {
    public static SessionLog.Type messageToSessionType(MessageType messageType) {
        return switch (messageType) {
            case USER -> USER;
            case ASSISTANT -> ASSISTANT;
            case SYSTEM -> SYSTEM;
            case TOOL -> SessionLog.Type.TOOL;
        };
    }

    public static MessageType sessionToMessageType(SessionLog.Type sessionType) {
        return switch (sessionType) {
            case USER -> MessageType.USER;
            case ASSISTANT, COMMERCIAL_TENANT -> MessageType.ASSISTANT;
            case SYSTEM -> MessageType.SYSTEM;
            case TOOL -> MessageType.TOOL;
        };
    }
    public static Message sessionToMessage(SessionLog.Type sessionType, String content) {
        return switch (sessionType) {
            case USER -> UserMessage.builder().text(content).build();
//            case ASSISTANT, COMMERCIAL_TENANT -> AssistantMessage.builder().content(content).build(); // 这个是SpringAI1.1.0+写法
            case ASSISTANT, COMMERCIAL_TENANT -> new AssistantMessage(content);
            case SYSTEM -> SystemMessage.builder().text(content).build();
            case TOOL -> null;
        };
    }
}
