package baize.code.java.service;

import com.baomidou.mybatisplus.extension.service.IService;
import baize.code.java.common.Result;
import baize.code.java.entity.SessionLog;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

public interface SessionLogService extends IService<SessionLog> {


    Result<?> readCtMessage(Integer sessionId, Integer userId);

    Result<?> readUserMessage(Integer sessionId, Integer ctId);

    Result<List<SessionLog>> getWindowMessage(Integer sessionId);

    Result<Integer> userGetUnreadMessageCount(Integer sessionId);

    Result<Integer> ctGetUnreadMessageCount(Integer sessionId);

    /**
     * 根据会话id获取到会话信息
     * @param conversationId
     * @return
     */
    List<SessionLog> tryGetSessionLog(String conversationId);

    /**
     * 添加信息到redis
     * @param 
     * @return
     */
    void addToRedis(String conversationId, List<SessionLog> sessionLogs);

    /**
     * 
     * @param conversationId
     * @param messages
     */
    void add(String conversationId, List<Message> messages);
    
}