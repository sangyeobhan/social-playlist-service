package com.codeit.sb02mplteam2.domain.livewatch.redis;

import com.codeit.sb02mplteam2.domain.livewatch.dto.response.ParticipantResponseDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class RedisLiveWatchParticipantServiceIntegrationTest {

  @Container
  static GenericContainer<?> redis = new GenericContainer<>("redis:7.0-alpine")
      .withExposedPorts(6379);

  @Autowired
  private RedisLiveWatchParticipantService participantService;

  @Autowired
  private StringRedisTemplate stringRedisTemplate;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", redis::getHost);
    registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379).toString());
  }

  @AfterEach
  void tearDown() {
    stringRedisTemplate.getConnectionFactory().getConnection().flushAll();
  }

  // ===== 기본 CRUD 테스트 =====

  @Test
  @DisplayName("사용자가 방에 입장하면 참가자 목록에서 조회되어야 한다")
  void joinRoom_BasicJoinAndRetrieve() {
    // given
    Long roomId = 100L;
    Long userId = 1001L;

    // when
    participantService.joinRoom(roomId, userId, "testUser", "profile.jpg");

    // then
    List<ParticipantResponseDto> participants = participantService.getParticipants(roomId);
    assertThat(participants).hasSize(1);

    ParticipantResponseDto participant = participants.get(0);
    assertThat(participant.userId()).isEqualTo(userId);
    assertThat(participant.userName()).isEqualTo("testUser");
    assertThat(participant.profileUrl()).isEqualTo("profile.jpg");
    assertThat(participant.participatedAt()).isNotNull();
  }

  @Test
  @DisplayName("사용자가 퇴장하면 참가자 목록에서 제거되어야 한다")
  void leaveRoom_BasicLeaveAndVerify() {
    // given
    Long roomId = 100L;
    Long userId = 1001L;
    participantService.joinRoom(roomId, userId, "testUser", "profile.jpg");

    // when
    participantService.leaveRoom(roomId, userId);

    // then
    assertThat(participantService.isAlreadyParticipating(roomId, userId)).isFalse();
    assertThat(participantService.getParticipantCount(roomId)).isEqualTo(0);
    assertThat(participantService.getParticipants(roomId)).isEmpty();
  }

  @Test
  @DisplayName("빈 방의 참가자 목록은 빈 리스트를 반환해야 한다")
  void getParticipants_EmptyRoom() {
    // when
    List<ParticipantResponseDto> participants = participantService.getParticipants(999L);

    // then
    assertThat(participants).isEmpty();
  }

  @Test
  @DisplayName("빈 방의 참가자 수는 0을 반환해야 한다")
  void getParticipantCount_EmptyRoom() {
    // when
    Integer count = participantService.getParticipantCount(999L);

    // then
    assertThat(count).isEqualTo(0);
  }

  // ===== 참가 상태 확인 테스트 =====

  @Test
  @DisplayName("입장한 사용자의 참가 상태는 true여야 한다")
  void isAlreadyParticipating_ReturnsTrueAfterJoin() {
    // given
    Long roomId = 100L;
    Long userId = 1001L;
    participantService.joinRoom(roomId, userId, "testUser", "profile.jpg");

    // when & then
    assertThat(participantService.isAlreadyParticipating(roomId, userId)).isTrue();
  }

  @Test
  @DisplayName("입장하지 않은 사용자의 참가 상태는 false여야 한다")
  void isAlreadyParticipating_ReturnsFalseWithoutJoin() {
    // when & then
    assertThat(participantService.isAlreadyParticipating(100L, 1001L)).isFalse();
  }

  // ===== 자동 퇴장 및 방 이동 테스트 =====

  @Test
  @DisplayName("사용자가 새로운 방에 입장할 때 기존 방에서 자동으로 퇴장되어야 한다")
  void joinRoom_AutoLeaveFromPreviousRoom() {
    // given
    Long previousRoomId = 200L;
    Long newRoomId = 300L;
    Long userId = 1001L;

    participantService.joinRoom(previousRoomId, userId, "testUser", "profile.jpg");

    // when
    participantService.joinRoom(newRoomId, userId, "testUser", "profile.jpg");

    // then
    assertThat(participantService.getCurrentRoom(userId)).isEqualTo(newRoomId);
    assertThat(participantService.isAlreadyParticipating(previousRoomId, userId)).isFalse();
    assertThat(participantService.isAlreadyParticipating(newRoomId, userId)).isTrue();
  }

  @Test
  @DisplayName("동일한 사용자가 중복 입장할 때 기존 데이터를 덮어써야 한다")
  void joinRoom_OverwriteExistingUser() {
    // given
    Long roomId = 100L;
    Long userId = 1001L;

    participantService.joinRoom(roomId, userId, "originalName", "original.jpg");

    // when
    participantService.joinRoom(roomId, userId, "updatedName", "updated.jpg");

    // then
    List<ParticipantResponseDto> participants = participantService.getParticipants(roomId);
    assertThat(participants).hasSize(1);

    ParticipantResponseDto participant = participants.get(0);
    assertThat(participant.userName()).isEqualTo("updatedName");
    assertThat(participant.profileUrl()).isEqualTo("updated.jpg");
  }

  // ===== 참가자 수 및 다수 참가자 테스트 =====

  @Test
  @DisplayName("참가자 수가 정확히 계산되어야 한다")
  void getParticipantCount_Accurate() {
    // given
    Long roomId = 100L;
    participantService.joinRoom(roomId, 1001L, "user1", "profile1.jpg");
    participantService.joinRoom(roomId, 1002L, "user2", "profile2.jpg");

    // when
    Integer count = participantService.getParticipantCount(roomId);

    // then
    assertThat(count).isEqualTo(2);
    assertThat(participantService.getParticipants(roomId)).hasSize(2);
  }

  @Test
  @DisplayName("같은 방에 여러 참가자가 올바르게 저장되어야 한다")
  void joinRoom_MultipleParticipantsInSameRoom() {
    // given
    Long roomId = 100L;
    participantService.joinRoom(roomId, 1001L, "user1", "profile1.jpg");
    participantService.joinRoom(roomId, 1002L, "user2", "profile2.jpg");
    participantService.joinRoom(roomId, 1003L, "user3", "profile3.jpg");

    // when
    List<ParticipantResponseDto> participants = participantService.getParticipants(roomId);

    // then
    assertThat(participants).hasSize(3);
    assertThat(participants)
        .extracting(ParticipantResponseDto::userName)
        .containsExactlyInAnyOrder("user1", "user2", "user3");
  }

  // ===== 현재 방 추적 테스트 =====

  @Test
  @DisplayName("getCurrentRoom이 방 이동 라이프사이클을 올바르게 추적해야 한다")
  void getCurrentRoom_TracksRoomLifecycle() {
    // given
    Long roomA = 100L;
    Long roomB = 200L;
    Long userId = 1001L;

    // when: 방 A 입장
    participantService.joinRoom(roomA, userId, "testUser", "profile.jpg");
    assertThat(participantService.getCurrentRoom(userId)).isEqualTo(roomA);

    // when: 방 B로 이동
    participantService.joinRoom(roomB, userId, "testUser", "profile.jpg");
    assertThat(participantService.getCurrentRoom(userId)).isEqualTo(roomB);

    // when: 퇴장
    participantService.leaveRoom(roomB, userId);
    assertThat(participantService.getCurrentRoom(userId)).isNull();
  }

  // ===== null/엣지 케이스 테스트 =====

  @Test
  @DisplayName("profileUrl이 null일 때 빈 문자열로 저장되어야 한다")
  void joinRoom_NullProfileUrl() {
    // given
    Long roomId = 100L;
    Long userId = 1001L;

    // when
    participantService.joinRoom(roomId, userId, "testUser", null);

    // then
    List<ParticipantResponseDto> participants = participantService.getParticipants(roomId);
    assertThat(participants).hasSize(1);
    assertThat(participants.get(0).profileUrl()).isEqualTo("");
  }

  // ===== 잘못된 데이터 내성 테스트 =====

  @Test
  @DisplayName("잘못된 JSON 데이터가 있을 때 해당 참가자를 제외하고 반환되어야 한다")
  void getParticipants_FilterOutInvalidJson() {
    // given
    Long roomId = 100L;
    String participantsKey = RedisKeyPatterns.roomParticipants(roomId);

    participantService.joinRoom(roomId, 1001L, "validUser", "profile.jpg");

    // 잘못된 JSON 직접 삽입
    stringRedisTemplate.opsForHash().put(participantsKey, "1002", "not-valid-json{{{");

    // when
    List<ParticipantResponseDto> participants = participantService.getParticipants(roomId);

    // then
    assertThat(participants).hasSize(1);
    assertThat(participants.get(0).userName()).isEqualTo("validUser");
  }

  @Test
  @DisplayName("숫자가 아닌 필드 키나 손상된 JSON이 있을 때 무시하고 처리되어야 한다")
  void getParticipants_IgnoreInvalidFieldKeyAndCorruptedJson() {
    // given
    Long roomId = 100L;
    String participantsKey = RedisKeyPatterns.roomParticipants(roomId);

    participantService.joinRoom(roomId, 1001L, "validUser", "profile.jpg");

    // 숫자가 아닌 필드 키
    stringRedisTemplate.opsForHash().put(participantsKey, "not-a-number",
        "{\"userName\":\"x\",\"profileUrl\":\"\",\"participatedAt\":\"2026-03-27T10:00:00\"}");
    // 유효한 키지만 손상된 JSON
    stringRedisTemplate.opsForHash().put(participantsKey, "9999", "corrupted-json");

    // when
    List<ParticipantResponseDto> participants = participantService.getParticipants(roomId);

    // then
    assertThat(participants).hasSize(1);
    assertThat(participants.get(0).userName()).isEqualTo("validUser");
  }

  @Test
  @DisplayName("유효한 JSON이지만 필수 필드가 누락되면 해당 참가자를 제외해야 한다")
  void getParticipants_FilterOutJsonMissingRequiredFields() {
    // given
    Long roomId = 100L;
    String participantsKey = RedisKeyPatterns.roomParticipants(roomId);

    participantService.joinRoom(roomId, 1001L, "validUser", "profile.jpg");

    // participatedAt 누락
    stringRedisTemplate.opsForHash().put(participantsKey, "5555",
        "{\"userName\":\"incomplete\"}");

    // when
    List<ParticipantResponseDto> participants = participantService.getParticipants(roomId);

    // then
    assertThat(participants).hasSize(1);
    assertThat(participants.get(0).userName()).isEqualTo("validUser");
  }

  // ===== TTL 테스트 =====

  @Test
  @DisplayName("입장 시 참가자 해시에 TTL이 설정되어야 한다")
  void joinRoom_TTLIsApplied() {
    // given
    Long roomId = 100L;
    String participantsKey = RedisKeyPatterns.roomParticipants(roomId);

    // when
    participantService.joinRoom(roomId, 1001L, "testUser", "profile.jpg");

    // then
    Long ttl = stringRedisTemplate.getExpire(participantsKey, TimeUnit.SECONDS);
    assertThat(ttl).isNotNull();
    assertThat(ttl).isGreaterThan(0);
    // 5시간 = 18000초, 약간의 오차 허용
    assertThat(ttl).isLessThanOrEqualTo(18000L);
  }
}
