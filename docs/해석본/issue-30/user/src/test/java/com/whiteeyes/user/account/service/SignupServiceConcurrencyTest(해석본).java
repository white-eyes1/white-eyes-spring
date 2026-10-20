/*
 * [해석본] 원본: user/src/test/java/com/whiteeyes/user/account/service/SignupServiceConcurrencyTest.java (feature/30-signup 브랜치)
 * 관련 이슈: #30(FNC-U-002 검수 기준 '동시에 같은 아이디로 가입 시 하나만 성공')
 *
 * [무엇] 검수 기준을 그대로 옮긴 테스트. 스레드 10개가 같은 아이디로 동시에 가입하면 1건 성공, 9건 DUPLICATE_LOGIN_ID,
 *   DB에는 1행만 남아야 한다. 진짜 서비스와 H2 DB를 쓴다(가짜 없음).
 * [정말 경쟁이 일어나나] 이 테스트를 잠깐 20번 반복(@RepeatedTest(20))하고 거절 이유를 세어 보았다: 20번 모두 통과,
 *   거절 180건 중 179건이 DB 유일 조건, 1건이 미리 확인이었다. 즉 대부분 둘 이상이 미리 확인을 통과했고 DB가 막았다.
 *   매번 이렇게 된다는 보장은 없어서, 'DB가 막는 경로'만 확실히 지나는 테스트는 SignupServiceTest에 따로 있다.
 */
package com.whiteeyes.user.account.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.whiteeyes.user.account.dto.SignupRequest;
import com.whiteeyes.user.account.exception.DuplicateLoginIdException;

// [해석] 테스트의 트랜잭션은 테스트 스레드에만 묶인다. 작업 스레드의 가입은 붙여도 따로 커밋되므로 롤백으로 정리되지 않는다.
//        그래서 붙이지 않고 @AfterEach에서 지운다. 실제 요청처럼 각 가입이 자기 트랜잭션으로 커밋해야 경쟁이 생긴다.
// 요청마다 자기 트랜잭션으로 커밋해야 경쟁이 생기므로 테스트에 @Transactional을 붙이지 않고 행은 직접 지운다
@SpringBootTest
class SignupServiceConcurrencyTest {

    private static final String LOGIN_ID = "hong01";
    private static final int THREAD_COUNT = 10;

    private final SignupService signupService;
    private final JdbcTemplate jdbcTemplate;

    SignupServiceConcurrencyTest(@Autowired SignupService signupService, @Autowired JdbcTemplate jdbcTemplate) {
        this.signupService = signupService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM user_account");
    }

    @Test
    @DisplayName("FNC-U-002 동시에 같은 아이디로 가입 시 하나만 성공")
    void signup_concurrentSameLoginId_onlyOneSucceeds() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        // [해석] ready: 10개 스레드가 모두 출발선에 설 때까지 기다린다. start: 그다음 한 번에 출발시킨다.
        //        스레드를 만드는 시간 차이 때문에 요청이 한 줄로 서 버리는 것을 줄인다.
        CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < THREAD_COUNT; i++) {
            // [해석] 아이디는 모두 같고 이름만 다르다. 이름은 검사에 쓰지 않는다(어느 요청이 성공했는지 볼 때 구분용).
            SignupRequest request = new SignupRequest(LOGIN_ID, "abcd1234", "abcd1234", "홍길동" + i, null, null);
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                return signupService.signup(request);
            }));
        }
        ready.await();
        start.countDown();

        int succeeded = 0;
        int duplicated = 0;
        List<Throwable> unexpected = new ArrayList<>();
        for (Future<?> future : futures) {
            try {
                // [해석] 시간 제한: 잠금 대기가 풀리지 않는 문제가 생기면 테스트가 멈춰 있지 않고 실패하게 한다.
                future.get(30, TimeUnit.SECONDS);
                succeeded++;
            } catch (ExecutionException ex) {
                if (ex.getCause() instanceof DuplicateLoginIdException) {
                    duplicated++;
                } else {
                    // [해석] 성공도 중복도 아닌 예외(잠금 시간 초과 등)를 모은다. H2는 잠금을 기본 1초까지 기다려서, 넘기면 이런 예외가 날 수 있다.
                    //        하나라도 있으면 아래 isEmpty에서 실패하고 어떤 예외였는지 메시지에 보인다.
                    unexpected.add(ex.getCause());
                }
            }
        }
        executor.shutdown();

        assertThat(unexpected).isEmpty();
        assertThat(succeeded).isEqualTo(1);
        assertThat(duplicated).isEqualTo(THREAD_COUNT - 1);
        // [해석] 응답 개수만이 아니라 DB에도 한 행만 남았는지 본다.
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_account WHERE login_id = ?", Integer.class, LOGIN_ID);
        assertThat(rows).isEqualTo(1);
    }
}
