package baize.code.java.service.impl;

import baize.code.java.service.SessionLogService;
import baize.code.java.utils.KeyUtils;
import baize.code.java.utils.TypeConversion;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import baize.code.java.code.ResultCode;
import baize.code.java.common.Result;
import baize.code.java.entity.Session;
import baize.code.java.entity.SessionLog;
import baize.code.java.mapper.SessionLogMapper;
import baize.code.java.mapper.SessionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class SessionLogServiceImpl extends ServiceImpl<SessionLogMapper, SessionLog> implements SessionLogService {
    private final StringRedisTemplate stringRedisTemplate;
    private final SessionMapper sessionMapper;
    @Value("${memory.redis-length}")
    private Integer memoryLength;
    @Value("${memory.expiration-duration}")
    private Integer timeout;
    @Value("${memory.key}")
    private String memoryKey;


    @Override
    public Result<?> readCtMessage(Integer sessionId, Integer userId) {
        // 检查是否有该会话
        Session session = sessionMapper.selectOne(new LambdaQueryWrapper<>(Session.class)
                .eq(Session::getId, sessionId)
                .eq(Session::getUserId, userId)
        );
        if (session == null) {
            return Result.error(ResultCode.NOT_FOUND);
        }
        // 修改这个session的关于商户或者AI发送的消息
        lambdaUpdate().set(SessionLog::getReadStatus, SessionLog.ReadStatus.READ)
                .eq(SessionLog::getSessionId, sessionId)
                .eq(SessionLog::getType, SessionLog.Type.ASSISTANT)
                .or()
                .eq(SessionLog::getType, SessionLog.Type.COMMERCIAL_TENANT)
                .update();
        return Result.success(ResultCode.UPDATE_SUCCESS);
    }

    @Override
    public Result<?> readUserMessage(Integer sessionId, Integer ctId) {
        // 检查是否有该会话
        Session session = sessionMapper.selectOne(new LambdaQueryWrapper<>(Session.class)
                .eq(Session::getId, sessionId)
                .eq(Session::getCtId, ctId)
        );
        if (session == null) {
            return Result.error(ResultCode.NOT_FOUND);
        }
        // 修改这个session的关于商户或者AI发送的消息
        lambdaUpdate().set(SessionLog::getReadStatus, SessionLog.ReadStatus.READ)
                .eq(SessionLog::getSessionId, sessionId)
                .eq(SessionLog::getType, SessionLog.Type.USER)
                .update();
        return Result.success(ResultCode.UPDATE_SUCCESS);
    }

    @Override
    public Result<List<SessionLog>> getWindowMessage(Integer sessionId) {
        return Result.success(
                ResultCode.GET_SUCCESS,
                lambdaQuery().eq(SessionLog::getSessionId, sessionId)
                       // .orderByAsc(SessionLog::getTimestamp)
                        .list()
        );
    }

    @Override
    public Result<Integer> userGetUnreadMessageCount(Integer sessionId) {
        return Result.success(
                ResultCode.GET_SUCCESS,
                lambdaQuery().eq(SessionLog::getSessionId, sessionId)
                        .eq(SessionLog::getType, SessionLog.Type.COMMERCIAL_TENANT)
                        .or()
                        .eq(SessionLog::getType, SessionLog.Type.ASSISTANT)
                        .count().intValue()
        );
    }

    @Override
    public Result<Integer> ctGetUnreadMessageCount(Integer sessionId) {
        return Result.success(
                ResultCode.GET_SUCCESS,
                lambdaQuery().eq(SessionLog::getSessionId, sessionId)
                        .eq(SessionLog::getType, SessionLog.Type.USER)
                        .count().intValue()
        );
    }

    /**
     * 根据会话id获取会话信息
     * @param conversationId
     * @return
     */
    @Override
    public List<SessionLog> tryGetSessionLog(String conversationId) {
        //从数据库中获取十条会话数据[分页查询]
        return lambdaQuery().eq(SessionLog::getSessionId, conversationId)
                .orderByDesc(SessionLog::getTimestamp)
                .page(new Page<SessionLog>(1,memoryLength))
                .getRecords();
    }

    /**
     * 将数据添加到redis
     * @param conversationId
     * @param sessionLogs
     */
    @Override
    public void addToRedis(String conversationId, List<SessionLog> sessionLogs) {
        //判断sessionLogs是否为空
        if(sessionLogs==null||sessionLogs.isEmpty()){
            return ;
        }
        //将sessionLogs存放到redis
        stringRedisTemplate.opsForList().rightPushAll(KeyUtils.redisKeyUtils(memoryKey,conversationId),
                sessionLogs.stream().map(JSONUtil::toJsonStr).toList());
        //重置key的时间
        stringRedisTemplate.expire(KeyUtils.redisKeyUtils(memoryKey,conversationId), timeout, TimeUnit.MINUTES);
        //检查redis中的list长度，如果超过设定的memory上下文长度则将删除
        if(Optional.ofNullable(stringRedisTemplate.opsForList().size(KeyUtils.redisKeyUtils(memoryKey,conversationId))).orElse(0L) > memoryLength){
            stringRedisTemplate.opsForList().trim(KeyUtils.redisKeyUtils(memoryKey,conversationId), -memoryLength, - 1);
        }
    }

    /**
     * 添加聊天到数据库和reids
     * @param conversationId
     * @param messages
     */
    @Override
    public void add(String conversationId, List<Message> messages) {
        //将message信息变成sessionLog信息,封装成一个集合
        List<SessionLog> sessionLogList = messages.stream().map(message -> {
            SessionLog.Type type = TypeConversion.messageToSessionType(message.getMessageType());
            //构建sessionLog数据
            return SessionLog.builder()
                    .type(type)
                    .sessionId(Integer.valueOf(conversationId))
                    .content(message.getText())
                    .build();
        }).toList();
        //保存数据到mysql
        saveBatch(sessionLogList);
        //保存数据到redis
        addToRedis(conversationId, sessionLogList);
    }
}