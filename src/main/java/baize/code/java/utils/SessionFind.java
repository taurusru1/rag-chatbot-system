package baize.code.java.utils;

import baize.code.java.entity.Session;
import baize.code.java.mapper.SessionMapper;
import baize.code.java.service.SessionService;
import baize.code.java.websocket.endpoint.CommercialTenantEndpoint;
import baize.code.java.websocket.endpoint.UserServiceEndpoint;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class SessionFind {

    @Value("${session.key}")
    private String key;
    @Value("${session.expiration-duration}")
    private Integer timeout;
    
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private SessionMapper sessionMapper;
    public  Session getSessionById(Integer sessionId){
        //从redis查询session
        String json = stringRedisTemplate.opsForValue().getAndExpire(KeyUtils.redisKeyUtils(key,sessionId), timeout, TimeUnit.MINUTES);
        if(json != null){
            return JSONUtil.toBean(json, Session.class);
        }
        //数据库查询session
        Session session = sessionMapper.selectById(sessionId);
        //将session保存到redis
        stringRedisTemplate.opsForValue().set(KeyUtils.redisKeyUtils(key,sessionId), JSONUtil.toJsonStr(session));
        //返回session
        return session;
    }

    /**
     * 查询用户的端点
     * @param sessionId
     */
    public UserServiceEndpoint findUSerServiceEndPoint(Integer sessionId) {
        Session session = getSessionById(sessionId);
        Integer sessionUserId = session.getUserId();

        UserServiceEndpoint endPoint = UserServiceEndpoint.findEndPoint(sessionUserId);
        return endPoint;
    }

    /**
     * 查询商户的端点
     */
    public CommercialTenantEndpoint findCommercialTenantEndPoint(Integer sessionId) {
        //查询session
        Session session = getSessionById(sessionId);
        //查询到session的ctid
        Integer ctId = session.getCtId();
        //根据ctid查到相应的商户端点
        CommercialTenantEndpoint commercialTenantEndpoint = CommercialTenantEndpoint.findEndPoint(ctId);
        return commercialTenantEndpoint;
    }
}
