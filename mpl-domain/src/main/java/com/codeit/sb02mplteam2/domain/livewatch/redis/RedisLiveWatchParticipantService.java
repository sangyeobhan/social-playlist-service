package com.codeit.sb02mplteam2.domain.livewatch.redis;

import com.codeit.sb02mplteam2.domain.livewatch.dto.response.ParticipantResponseDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class RedisLiveWatchParticipantService {

  private final StringRedisTemplate stringRedisTemplate;
  private final ObjectMapper objectMapper;

  private record ParticipantData(
      String userName,
      String profileUrl,
      LocalDateTime participatedAt
  ) {}

  public void joinRoom(Long roomId, Long userId, String username, String profileUrl) {
    String participantsKey = RedisKeyPatterns.roomParticipants(roomId);
    String userField = RedisKeyPatterns.userField(userId);
    String currentRoomKey = RedisKeyPatterns.userCurrentRoom(userId);
    Duration ttl = RedisTTLStrategy.PARTICIPANT_SESSION.getDuration();

    // Phase 1: 이전 방 확인 (동기 — 결과에 따라 HDEL 여부 결정)
    Long oldRoomId = getCurrentRoom(userId);

    ParticipantData data = new ParticipantData(
        username,
        profileUrl != null ? profileUrl : "",
        LocalDateTime.now()
    );

    String json;
    try {
      json = objectMapper.writeValueAsString(data);
    } catch (JsonProcessingException e) {
      throw new RuntimeException("참가자 데이터 직렬화 실패", e);
    }

    // Phase 2: 파이프라인 (1 RT)
    stringRedisTemplate.executePipelined(new SessionCallback<Object>() {
      @Override
      public <K, V> Object execute(RedisOperations<K, V> operations) throws DataAccessException {
        RedisOperations<String, String> ops = (RedisOperations<String, String>) operations;
        if (oldRoomId != null && !oldRoomId.equals(roomId)) {
          String oldKey = RedisKeyPatterns.roomParticipants(oldRoomId);
          ops.opsForHash().delete(oldKey, userField);
        }
        ops.opsForHash().put(participantsKey, userField, json);
        ops.expire(participantsKey, ttl);
        ops.opsForValue().set(currentRoomKey, roomId.toString());
        ops.expire(currentRoomKey, ttl);
        return null;
      }
    });

    log.info("사용자 {}가 채팅방 {}에 입장", userId, roomId);
  }

  public void leaveRoom(Long roomId, Long userId) {
    String participantsKey = RedisKeyPatterns.roomParticipants(roomId);
    String userField = RedisKeyPatterns.userField(userId);
    String currentRoomKey = RedisKeyPatterns.userCurrentRoom(userId);

    stringRedisTemplate.executePipelined(new SessionCallback<Object>() {
      @Override
      public <K, V> Object execute(RedisOperations<K, V> operations) throws DataAccessException {
        RedisOperations<String, String> ops = (RedisOperations<String, String>) operations;
        ops.opsForHash().delete(participantsKey, userField);
        ops.delete(currentRoomKey);
        return null;
      }
    });

    log.info("Redis: 사용자 {}가 채팅방 {}에서 퇴장", userId, roomId);
  }

  public List<ParticipantResponseDto> getParticipants(Long roomId) {
    String participantsKey = RedisKeyPatterns.roomParticipants(roomId);

    Map<Object, Object> allFields = stringRedisTemplate.opsForHash().entries(participantsKey);

    if (allFields.isEmpty()) {
      return List.of();
    }

    return allFields.entrySet().stream()
        .map(entry -> parseParticipant(entry.getKey().toString(), entry.getValue().toString()))
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
  }

  public Integer getParticipantCount(Long roomId) {
    String participantsKey = RedisKeyPatterns.roomParticipants(roomId);

    Long fieldCount = stringRedisTemplate.opsForHash().size(participantsKey);
    if (fieldCount == null || fieldCount == 0) {
      return 0;
    }
    return fieldCount.intValue();
  }

  public boolean isAlreadyParticipating(Long roomId, Long userId) {
    String participantsKey = RedisKeyPatterns.roomParticipants(roomId);
    String userField = RedisKeyPatterns.userField(userId);

    return stringRedisTemplate.opsForHash().hasKey(participantsKey, userField);
  }

  public Long getCurrentRoom(Long userId) {
    String currentRoomKey = RedisKeyPatterns.userCurrentRoom(userId);

    String roomIdStr = stringRedisTemplate.opsForValue().get(currentRoomKey);
    return roomIdStr != null ? Long.parseLong(roomIdStr) : null;
  }

  private ParticipantResponseDto parseParticipant(String fieldKey, String jsonValue) {
    try {
      Long userId = Long.parseLong(fieldKey);
      ParticipantData data = objectMapper.readValue(jsonValue, ParticipantData.class);
      return new ParticipantResponseDto(
          userId,
          data.userName(),
          data.profileUrl(),
          data.participatedAt()
      );
    } catch (Exception e) {
      log.error("참가자 정보 파싱 실패: key={}, value={}", fieldKey, jsonValue, e);
      return null;
    }
  }
}
