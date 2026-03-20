package baize.code.java.ai.service;

import baize.code.java.websocket.endpoint.UserServiceEndpoint;
import baize.code.java.websocket.message.ChatMessage;
import jakarta.websocket.EncodeException;
import jakarta.websocket.Session;

import java.io.IOException;

public interface AIService {
    /**
     * 判断是否需要转ai
     * @param message
     * @param session1
     */
    void turnToManualJudgment(ChatMessage message, baize.code.java.entity.Session session1);

    /**
     * ai对话
     * @param session
     * @param message
     * @param userServiceEndpoint
     */
    void chat(Session session, ChatMessage message, UserServiceEndpoint userServiceEndpoint) throws IllegalAccessException, EncodeException, IOException;
    
}
