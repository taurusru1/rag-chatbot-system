package baize.code.java.websocket.endpoint;

import baize.code.java.config.ChatMessageCoder;
import baize.code.java.entity.SessionLog;
import baize.code.java.mapper.SessionLogMapper;
import baize.code.java.utils.SessionFind;
import baize.code.java.websocket.message.ChatMessage;
import jakarta.websocket.*;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

// TODO:006
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
@Component  //变成组件
@ServerEndpoint(value = "/commercialTenant/chat/{ctId}",decoders = ChatMessageCoder.class, encoders = ChatMessageCoder.class)
public class CommercialTenantEndpoint implements WebSocketEndpoint {
    //定义一个断点池，来存放端点
    private static final ConcurrentHashMap<Integer, CommercialTenantEndpoint> commercialTenantEndpointConcurrentEndpointPool = new ConcurrentHashMap<>();
    private static SessionLogMapper sessionLogMapper;
    private static SessionFind sessionFind;

    private Session session;
    private Integer ctId;

    @Autowired
    public void setDependency(SessionLogMapper sessionLogMapper, SessionFind sessionFind) {
        CommercialTenantEndpoint.sessionLogMapper = sessionLogMapper;
        CommercialTenantEndpoint.sessionFind =  sessionFind;
    }

    @OnOpen //连接时触发
    public void onOpen(Session session, @PathParam("ctId") Integer ctId) {
        this.session = session;
        this.ctId = ctId;
        //链接成功后，会将传过来的商户id自动存放到断点池中
        commercialTenantEndpointConcurrentEndpointPool.put(ctId, this);
    }

    @OnClose //关闭时触发
    public void onClose() {
        if (ctId != null) {
            //删除成功后会自动将端点池的商户id删除
            commercialTenantEndpointConcurrentEndpointPool.remove(ctId);
        }
    }

    @OnMessage //发送消息时触发
    public void onMessage(ChatMessage message, Session session) throws EncodeException, IOException {
        message.setType(getEndpointType()); //设置消息的类型【这里是商户端的类型】

        //保存消息到数据库
        sessionLogMapper.insert(SessionLog.builder()
                .type(getEndpointType())
                .sessionId(message.getSessionId())
                .content(message.getMessage())
                .build());
        //找到相应用户的端点
        UserServiceEndpoint userServiceEndPoint = sessionFind.findUSerServiceEndPoint(message.getSessionId());
        //发送信息
        if(userServiceEndPoint!=null){
            userServiceEndPoint.sendMessage(message);
        }
    }

    @OnError //出现错误时触发
    public void onError(Session session, Throwable error) {
        try {
            error.printStackTrace();  //打印错误信息
            session.getBasicRemote().sendObject(ChatMessage.builder()
                    .state(ChatMessage.State.ERROR)
                    .message(error.getMessage())
                    .build()
            );
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
        return SessionLog.Type.COMMERCIAL_TENANT;
    }

    public static CommercialTenantEndpoint findEndPoint(Integer userId) {
        return commercialTenantEndpointConcurrentEndpointPool.get(userId);
    }
}
