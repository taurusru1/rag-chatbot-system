package baize.code.java.ai.memory;

import baize.code.java.entity.SessionLog;
import baize.code.java.executor.GlobalTreadPool;
import baize.code.java.service.SessionLogService;
import baize.code.java.utils.KeyUtils;
import baize.code.java.utils.TypeConversion;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class CustomizationMemory implements ChatMemory {
    
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private SessionLogService sessionLogService;
    @Resource
    
    @Value("${memory.key}")
    private String memoryKey;
    @Value("${memory.expiration-duration}")
    private Integer timeout;
    @Value("${memory.redis-length}")
    private Integer memoryLength;
    @Override
    public void add(String conversationId, List<Message> messages) {
        GlobalTreadPool.executor.execute(()->sessionLogService.add(conversationId,messages));
    }

    /**
     * 获取记忆
     * @param conversationId
     * @return
     */
    @Override
    public List<Message> get(String conversationId) {
        //从redis查询记忆
        List<String> memoryJson = stringRedisTemplate.opsForList().range(KeyUtils.redisKeyUtils(memoryKey, conversationId), -memoryLength, -1);
        List<Message> messages = null;
        if(CollUtil.isEmpty(memoryJson)){
            //从数据库查询记忆
            List<SessionLog> sessionLogs=sessionLogService.tryGetSessionLog(conversationId);
            //将查询出来的sessionLogs数据使用异步线程的方法添加到到redis
            GlobalTreadPool.executor.execute(new Runnable() {
                @Override
                public void run() {
                   sessionLogService.addToRedis(conversationId,sessionLogs);
                }
            });
            //将sessionLog转化为Message返回
            messages = new ArrayList<>(sessionLogs.size());
            for (SessionLog sessionLog : sessionLogs) {
                Message message = TypeConversion.sessionToMessage(sessionLog.getType(), sessionLog.getContent());
                messages.add(message);
            }
            return  messages;
        }
        //如果在在redis中查询到了之后就可以进行json->session->Message类型
        messages = new ArrayList<>(memoryJson.size());
        for (String json : memoryJson) {
            SessionLog sessionLog = JSONUtil.toBean(json, SessionLog.class); //转为session对象
            Message message = TypeConversion.sessionToMessage(sessionLog.getType(), sessionLog.getContent()); //转为Message对象
            messages.add(message);
        }
        return  messages;
    }

    @Override
    public void clear(String conversationId) {

    }
}
