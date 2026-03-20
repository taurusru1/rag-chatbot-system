package baize.code.java.service.impl;

import baize.code.java.code.ResultCode;
import baize.code.java.common.Result;
import baize.code.java.entity.Goods;
import baize.code.java.entity.Role;
import baize.code.java.entity.Session;
import baize.code.java.mapper.GoodsMapper;
import baize.code.java.mapper.SessionMapper;
import baize.code.java.service.RoleService;
import baize.code.java.service.SessionService;
import baize.code.java.utils.KeyUtils;
import baize.code.java.utils.SessionFind;
import baize.code.java.websocket.endpoint.UserServiceEndpoint;
import baize.code.java.websocket.message.ChatMessage;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import jakarta.websocket.EncodeException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class SessionServiceImpl extends ServiceImpl<SessionMapper, Session> implements SessionService {

    @Resource
    private RoleService roleService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private SessionFind sessionFind;

    @Resource
    private GoodsMapper goodsMapper;

    @Value("${session.key}")
    private String key;

    @Value("${session.expiration-duration}")
    private Integer timeout;

    @Override
    public Result<List<Session>> userGetLastSessionList(Integer userId) {
        return Result.success(
                ResultCode.GET_SUCCESS,
                lambdaQuery()
                        .eq(Session::getUserId, userId)
                        .orderByDesc(Session::getTimestamp)
                        .list()
        );
    }

    @Override
    public Result<List<Session>> ctGetLastSessionList(Integer ctId) {
        return Result.success(
                ResultCode.GET_SUCCESS,
                lambdaQuery()
                        .eq(Session::getCtId, ctId)
                        .orderByDesc(Session::getTimestamp)
                        .list()
        );
    }

    @Override
    public Session find(ChatMessage message, Integer userId, UserServiceEndpoint userServiceEndpoint)
            throws EncodeException, IOException {
        Integer sessionId = message.getSessionId();
        if (sessionId != null) {
            return sessionFind.getSessionById(sessionId);
        }

        if (message.getCtId() == null || message.getGoodsId() == null) {
            throw new RuntimeException("缺失必要参数，无法创建会话");
        }

        Goods goods = goodsMapper.selectById(message.getGoodsId());
        if (goods == null) {
            throw new RuntimeException("商品不存在，无法创建会话: goodsId=" + message.getGoodsId());
        }
        if (!goods.getCtId().equals(message.getCtId())) {
            throw new RuntimeException("商品与商户不匹配: goodsId=" + message.getGoodsId()
                    + ", goods.ctId=" + goods.getCtId()
                    + ", request.ctId=" + message.getCtId());
        }

        Session.SessionBuilder sessionBuilder = Session.builder()
                .ctId(message.getCtId())
                .userId(userId)
                .goodsId(message.getGoodsId());

        Role role = roleService.getRoleById(message.getCtId());
        if (role == null) {
            sessionBuilder.conversationStatus(Session.ConversationStatus.HUMAN);
        } else {
            sessionBuilder.conversationStatus(Session.ConversationStatus.AI);
        }

        Session session = sessionBuilder.build();
        save(session);

        stringRedisTemplate.opsForValue().set(
                KeyUtils.redisKeyUtils(key, session.getId()),
                JSONUtil.toJsonStr(session),
                timeout,
                TimeUnit.MINUTES
        );

        userServiceEndpoint.sendMessage(ChatMessage.builder()
                .sessionId(session.getId())
                .state(ChatMessage.State.SURE)
                .build());

        message.setSessionId(session.getId());
        return session;
    }
}
