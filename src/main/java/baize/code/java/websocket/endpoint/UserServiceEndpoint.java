package baize.code.java.websocket.endpoint;

import baize.code.java.ai.service.AIService;
import baize.code.java.config.ChatMessageCoder;
import baize.code.java.mapper.SessionLogMapper;
import baize.code.java.service.SessionService;
import baize.code.java.utils.SessionFind;
import baize.code.java.websocket.message.ChatMessage;
import baize.code.java.entity.SessionLog;
import jakarta.annotation.Resource;
import jakarta.websocket.*;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

import static baize.code.java.entity.Session.ConversationStatus.AI;
import static baize.code.java.entity.Session.ConversationStatus.HUMAN;

@Component
@ServerEndpoint(value = "/user/chat/{userId}", decoders = ChatMessageCoder.class, encoders = ChatMessageCoder.class)
public class UserServiceEndpoint implements WebSocketEndpoint {
    private static final ConcurrentHashMap<Integer, UserServiceEndpoint> userEndpointPool = new ConcurrentHashMap<>();

    /**
     * @ServerEndpoint 注解的类不是由 Spring 容器管理的，而是由 WebSocket 容器（Tomcat）创建的实例
     */
//    @Resource
//    private SessionService sessionService;
            
    private static SessionService sessionService;
    private static SessionLogMapper sessionLogMapper;
    private static SessionFind sessionFind;
    private static AIService aiService;

    @Autowired
     public void setDependency(SessionService sessionService,SessionLogMapper sessionLogMapper,SessionFind sessionFind,AIService aiService){
        UserServiceEndpoint.sessionService=sessionService;
        UserServiceEndpoint.sessionLogMapper =sessionLogMapper;
        UserServiceEndpoint.sessionFind=sessionFind;
        UserServiceEndpoint.aiService = aiService;
    }
     
    private Session session;
    private Integer userId;

    @OnOpen
    public void onOpen(Session session, @PathParam("userId") Integer userId) {
        this.session = session;
        this.userId = userId;
        userEndpointPool.put(userId, this);
    }

    @OnClose
    public void onClose() {
        if (userId != null) {
            userEndpointPool.remove(userId);
        }
    }

    // TODO:005
    @OnMessage
    public void onMessage(ChatMessage message, Session session) throws EncodeException, IOException, IllegalAccessException {
        message.setType(getEndpointType());

        //找到相应的会话
        baize.code.java.entity.Session session1 = sessionService.find(message, userId, this);
        System.out.println("当前会话状态: " + session1.getConversationStatus() + ", 会话ID: " + session1.getId());
        //查询会话的状态 ->是跟人还是AI
        switch (session1.getConversationStatus()){
            case AI ->{
                //判断是否需要转人工
               aiService.turnToManualJudgment(message, session1);
               if(session1.getConversationStatus() == HUMAN){
                   //被ai识别要转人工
                   CommercialTenantEndpoint commercialTenantEndPoint = sessionFind.findCommercialTenantEndPoint(message.getSessionId());
                   if(commercialTenantEndPoint!=null){
                       commercialTenantEndPoint.sendMessage(message);
                   }
               }else{
                   //走ai的对话
                   aiService.chat(session,message,this);
               }
            }
            case HUMAN -> {
                //human的情况下，将消息存储到sessionlog ,不需要存入redis
                SessionLog sessionLog = SessionLog.builder()
                        .type(message.getType())
                        .sessionId(message.getSessionId())
                        .content(message.getMessage())
                        .build();
                sessionLogMapper.insert(sessionLog);
                //根据session找到相应的端点  ->进行编写查找端点的业务代码
                CommercialTenantEndpoint commercialTenantEndPoint = sessionFind.findCommercialTenantEndPoint(message.getSessionId());
                //使用端点继续发送信息
                if(commercialTenantEndPoint!=null){
                    commercialTenantEndPoint.sendMessage(message);
                }
            }
        }
        
        
        
    }

    @OnError
    public void onError(Session session, Throwable error) {
        try {
            error.printStackTrace();
            session.getBasicRemote().sendObject(ChatMessage.builder()
                    .state(ChatMessage.State.ERROR)
                    .message(error.getMessage())
                    .build());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 封装发送消息的方法
     */
    public void sendMessage(ChatMessage chatMessage) throws EncodeException, IOException {
        this.session.getBasicRemote().sendObject(chatMessage);
    }

    @Override
    public SessionLog.Type getEndpointType() {
        return SessionLog.Type.USER;
    }

    public static UserServiceEndpoint findEndPoint(Integer userId) {
        return userEndpointPool.get(userId);
    }

    @Autowired
    public void setSessionLogMapper(SessionLogMapper sessionLogMapper) {
        this.sessionLogMapper = sessionLogMapper;
    }

    @Autowired
    public void setSessionFind(SessionFind sessionFind) {
        this.sessionFind = sessionFind;
    }
}
