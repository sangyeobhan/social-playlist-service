package com.codeit.sb02mplteam2.domain.livewatch.redis;

public final class RedisKeyPatterns {

  private static final String BASE_PREFIX = "livewatch";

  // 단일 Hash로 통합 - 참가자 정보 모두 포함
  // Hash fields: "456" → JSON string (userId가 필드 키)
  public static final String ROOM_PARTICIPANTS = BASE_PREFIX + ":room:%d:participants";

  // 사용자 현재 참가방 추적 (String - roomId)
  public static final String USER_CURRENT_ROOM = BASE_PREFIX + ":user:%d:current-room";

  private RedisKeyPatterns() {
  }

  public static String roomParticipants(Long roomId) {
    return String.format(ROOM_PARTICIPANTS, roomId);
  }

  public static String userCurrentRoom(Long userId) {
    return String.format(USER_CURRENT_ROOM, userId);
  }

  // Hash 필드 키 생성 (userId 자체가 필드 키)
  public static String userField(Long userId) {
    return String.valueOf(userId);
  }
}