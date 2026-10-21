package com.whiteeyes.admin.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.whiteeyes.common.history.dto.WorkHistoryCommand;
import com.whiteeyes.common.history.entity.ActorType;
import com.whiteeyes.common.history.entity.WorkAction;
import com.whiteeyes.common.history.entity.WorkHistory;
import com.whiteeyes.common.history.entity.WorkTargetType;
import com.whiteeyes.common.history.repository.WorkHistoryRepository;
import com.whiteeyes.common.history.service.WorkHistoryService;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class WorkHistoryServiceTest {

    // actor_login_id는 VARCHAR(20)이라 21자는 저장 단계에서 실패한다
    private static final String TOO_LONG_LOGIN_ID = "a".repeat(21);

    private final WorkHistoryService workHistoryService;
    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbcTemplate;

    WorkHistoryServiceTest(
            @Autowired WorkHistoryService workHistoryService,
            @Autowired TransactionTemplate transactionTemplate,
            @Autowired JdbcTemplate jdbcTemplate) {
        this.workHistoryService = workHistoryService;
        this.transactionTemplate = transactionTemplate;
        this.jdbcTemplate = jdbcTemplate;
    }

    @BeforeEach
    void clearTables() {
        jdbcTemplate.update("DELETE FROM work_history");
        // 롤백 확인용 표. DDL은 바로 커밋되므로 트랜잭션 밖에서 만든다
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS tx_probe (id INT)");
        jdbcTemplate.update("DELETE FROM tx_probe");
    }

    // 같은 메모리 DB를 쓰는 다른 테스트에 기록이 남지 않게 한다
    @AfterEach
    void dropProbe() {
        jdbcTemplate.update("DELETE FROM work_history");
        jdbcTemplate.execute("DROP TABLE IF EXISTS tx_probe");
    }

    @Test
    @DisplayName("FNC-C-026 기록 대상 행동을 실행하면 work_history에 1행이 생기고 값이 들어간다")
    void record_savesOneRow() {
        WorkHistoryCommand command = new WorkHistoryCommand(
                ActorType.ADMIN,
                7L,
                "super-admin",
                WorkAction.ACCOUNT_CHANGE,
                WorkTargetType.USER_ACCOUNT,
                12L,
                "user01",
                "{\"password\":\"변경됨\"}");

        transactionTemplate.executeWithoutResult(status -> workHistoryService.record(command));

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM work_history");
        assertThat(row.get("ACTOR_TYPE")).isEqualTo("ADMIN");
        assertThat(row.get("ACTOR_ID")).isEqualTo(7L);
        assertThat(row.get("ACTOR_LOGIN_ID")).isEqualTo("super-admin");
        assertThat(row.get("ACTION")).isEqualTo("ACCOUNT_CHANGE");
        assertThat(row.get("TARGET_TYPE")).isEqualTo("USER_ACCOUNT");
        assertThat(row.get("TARGET_ID")).isEqualTo(12L);
        assertThat(row.get("TARGET_NAME")).isEqualTo("user01");
        assertThat(new String((byte[]) row.get("DETAIL"), StandardCharsets.UTF_8)).contains("변경됨");
        assertThat(row.get("CREATED_AT")).isNotNull();
    }

    @Test
    @DisplayName("FNC-C-026 대상 번호·대상 이름·상세가 없어도 저장된다(전체 엑셀 다운로드)")
    void record_nullableColumnsMayBeNull() {
        WorkHistoryCommand command = new WorkHistoryCommand(
                ActorType.ADMIN, 7L, "super-admin", WorkAction.EXCEL_DOWNLOAD, WorkTargetType.ALL, null, null, null);

        transactionTemplate.executeWithoutResult(status -> workHistoryService.record(command));

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM work_history");
        assertThat(row.get("TARGET_ID")).isNull();
        assertThat(row.get("TARGET_NAME")).isNull();
        assertThat(row.get("DETAIL")).isNull();
    }

    @Test
    @DisplayName("FNC-C-026 action 6개와 target_type 7개를 모두 저장하고 읽을 수 있다")
    void record_allActionsAndTargetTypes() {
        transactionTemplate.executeWithoutResult(status -> {
            for (WorkAction action : WorkAction.values()) {
                workHistoryService.record(command(ActorType.ADMIN, action, WorkTargetType.ALL));
            }
            for (WorkTargetType targetType : WorkTargetType.values()) {
                workHistoryService.record(command(ActorType.USER, WorkAction.EVENT_STATUS, targetType));
            }
        });

        assertThat(WorkAction.values()).hasSize(6);
        assertThat(WorkTargetType.values()).hasSize(7);
        assertThat(jdbcTemplate.queryForList("SELECT DISTINCT action FROM work_history", String.class))
                .containsExactlyInAnyOrder(
                        "EXCEL_DOWNLOAD",
                        "EVENT_STATUS",
                        "ACCOUNT_CHANGE",
                        "CAMERA_RUN",
                        "ZONE_CHANGE",
                        "CAMERA_CHANGE");
        assertThat(jdbcTemplate.queryForList("SELECT DISTINCT target_type FROM work_history", String.class))
                .containsExactlyInAnyOrder(
                        "USER_ACCOUNT", "ADMIN_ACCOUNT", "ZONE", "CAMERA", "EVENT", "REGION", "ALL");
        assertThat(jdbcTemplate.queryForList("SELECT DISTINCT actor_type FROM work_history", String.class))
                .containsExactlyInAnyOrder("ADMIN", "USER");
    }

    @Test
    @DisplayName("FNC-C-026 호출한 쪽이 롤백하면 기록도 사라진다")
    void record_rollsBackWithCaller() {
        transactionTemplate.executeWithoutResult(status -> {
            workHistoryService.record(command(ActorType.ADMIN, WorkAction.CAMERA_RUN, WorkTargetType.CAMERA));
            status.setRollbackOnly();
        });

        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-C-026 기록 저장이 실패하면 원래 작업도 취소된다")
    void record_failureCancelsOriginalWork() {
        WorkHistoryCommand invalid = new WorkHistoryCommand(
                ActorType.ADMIN,
                7L,
                TOO_LONG_LOGIN_ID,
                WorkAction.ZONE_CHANGE,
                WorkTargetType.ZONE,
                1L,
                "zone",
                null);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
                    jdbcTemplate.update("INSERT INTO tx_probe (id) VALUES (1)");
                    workHistoryService.record(invalid);
                }))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(count("tx_probe")).isZero();
        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-C-026 호출한 쪽이 기록 저장 실패를 잡아도 원래 작업은 취소된다")
    void record_failureSwallowedByCaller_stillCancelsOriginalWork() {
        WorkHistoryCommand invalid = new WorkHistoryCommand(
                ActorType.ADMIN,
                7L,
                TOO_LONG_LOGIN_ID,
                WorkAction.ZONE_CHANGE,
                WorkTargetType.ZONE,
                1L,
                "zone",
                null);
        AtomicReference<RuntimeException> swallowed = new AtomicReference<>();

        // 기록 저장이 실패하면 트랜잭션이 rollback-only가 되어 호출한 쪽이 예외를 잡아도 커밋되지 않는다
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
                    jdbcTemplate.update("INSERT INTO tx_probe (id) VALUES (1)");
                    try {
                        workHistoryService.record(invalid);
                    } catch (RuntimeException e) {
                        swallowed.set(e);
                    }
                }))
                .isInstanceOf(UnexpectedRollbackException.class);

        assertThat(swallowed.get()).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("tx_probe")).isZero();
        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-C-026 트랜잭션 없이 부르면 거부된다")
    void record_withoutTransaction_rejected() {
        WorkHistoryCommand command = command(ActorType.ADMIN, WorkAction.CAMERA_RUN, WorkTargetType.CAMERA);

        assertThatThrownBy(() -> workHistoryService.record(command))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(count("work_history")).isZero();
    }

    @Test
    @DisplayName("FNC-C-026 INSERT만 한다: 서비스에는 record, 리포지토리에는 save뿐이고 엔티티에는 setter가 없다")
    void insertOnly_noUpdateOrDeletePath() {
        List<String> serviceMethods = Arrays.stream(WorkHistoryService.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .toList();
        List<String> repositoryMethods = Arrays.stream(WorkHistoryRepository.class.getMethods())
                .map(Method::getName)
                .toList();
        List<String> entitySetters = Arrays.stream(WorkHistory.class.getDeclaredMethods())
                .map(Method::getName)
                .filter(name -> name.startsWith("set"))
                .toList();

        assertThat(serviceMethods).containsExactly("record");
        assertThat(repositoryMethods).containsExactly("save");
        assertThat(entitySetters).isEmpty();
    }

    private WorkHistoryCommand command(ActorType actorType, WorkAction action, WorkTargetType targetType) {
        return new WorkHistoryCommand(actorType, 1L, "tester", action, targetType, 1L, "target", null);
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}
