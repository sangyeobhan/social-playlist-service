package com.codeit.sb02mplteam2.domain.livewatch.redis;

import com.codeit.sb02mplteam2.domain.livewatch.dto.response.ParticipantResponseDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

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
    leaveFromCurrentRoom(userId);

    String participantsKey = RedisKeyPatterns.roomParticipants(roomId);
    String userField = RedisKeyPatterns.userField(userId);

    ParticipantData data = new ParticipantData(
        username,
        profileUrl != null ? profileUrl : "",
        LocalDateTime.now()
    );

    try {
      String json = objectMapper.writeValueAsString(data);
      stringRedisTemplate.opsForHash().put(participantsKey, userField, json);
    } catch (JsonProcessingException e) {
      throw new RuntimeException("참가자 데이터 직렬화 실패", e);
    }

    stringRedisTemplate.expire(participantsKey, RedisTTLStrategy.PARTICIPANT_SESSION.getDuration());

    setCurrentRoom(userId, roomId);

    log.info("사용자 {}가 채팅방 {}에 입장", userId, roomId);
  }

  public void leaveRoom(Long roomId, Long userId) {
    String participantsKey = RedisKeyPatterns.roomParticipants(roomId);
    String userField = RedisKeyPatterns.userField(userId);

    stringRedisTemplate.opsForHash().delete(participantsKey, userField);

    clearCurrentRoom(userId);

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

  private void setCurrentRoom(Long userId, Long roomId) {
    String currentRoomKey = RedisKeyPatterns.userCurrentRoom(userId);

    stringRedisTemplate.opsForValue().set(currentRoomKey, roomId.toString());

    stringRedisTemplate.expire(currentRoomKey, RedisTTLStrategy.PARTICIPANT_SESSION.getDuration());
  }

  private void clearCurrentRoom(Long userId) {
    String currentRoomKey = RedisKeyPatterns.userCurrentRoom(userId);

    stringRedisTemplate.delete(currentRoomKey);
  }

  private void leaveFromCurrentRoom(Long userId) {
    Long currentRoomId = getCurrentRoom(userId);
    if (currentRoomId != null) {
      leaveRoom(currentRoomId, userId);
      log.info("Redis: 사용자 {}를 이전 채팅방 {}에서 제거", userId, currentRoomId);
    }
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
