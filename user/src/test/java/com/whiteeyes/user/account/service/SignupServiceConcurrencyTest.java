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
        CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < THREAD_COUNT; i++) {
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
                future.get(30, TimeUnit.SECONDS);
                succeeded++;
            } catch (ExecutionException ex) {
                if (ex.getCause() instanceof DuplicateLoginIdException) {
                    duplicated++;
                } else {
                    unexpected.add(ex.getCause());
                }
            }
        }
        executor.shutdown();

        assertThat(unexpected).isEmpty();
        assertThat(succeeded).isEqualTo(1);
        assertThat(duplicated).isEqualTo(THREAD_COUNT - 1);
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_account WHERE login_id = ?", Integer.class, LOGIN_ID);
        assertThat(rows).isEqualTo(1);
    }
}
