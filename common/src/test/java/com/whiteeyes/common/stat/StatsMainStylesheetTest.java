package com.whiteeyes.common.stat;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * 접은 통계 패널의 '통계' 버튼이 지도 범례에 가려 눌리지 않던 문제를 막는다(FNC-C-016, 이슈 #258).
 * 캠 패널을 펼치면 지도 칸이 64px까지 얇아져 왼쪽 아래 버튼과 왼쪽 위 범례가 겹친다.
 */
class StatsMainStylesheetTest {

    private static final Pattern Z_INDEX = Pattern.compile("z-index:\\s*(\\d+)");

    private static String css;

    @BeforeAll
    static void loadStylesheet() throws IOException {
        try (InputStream in = new ClassPathResource("static/css/stats-main.css").getInputStream()) {
            css = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 선택자가 정확히 일치하는 규칙의 선언부를 돌려준다 */
    private static String declarations(String selector) {
        Matcher matcher = Pattern.compile("(?m)^" + Pattern.quote(selector) + "\\s*\\{([^}]*)\\}").matcher(css);
        assertThat(matcher.find()).as("규칙 '%s'", selector).isTrue();
        return matcher.group(1);
    }

    private static int zIndex(String selector) {
        Matcher matcher = Z_INDEX.matcher(declarations(selector));
        assertThat(matcher.find()).as("'%s'의 z-index", selector).isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    @Test
    @DisplayName("FNC-C-016 '통계' 버튼은 범례보다 위에 있어 겹쳐도 눌린다")
    void panelOpenButton_isAboveLegend() {
        assertThat(zIndex(".panel-open")).isGreaterThan(zIndex(".map-legend"));
    }

    @Test
    @DisplayName("FNC-C-016 캠 패널을 펼치면 '통계' 버튼을 위쪽으로 옮긴다")
    void expandedDash_movesPanelOpenButtonToTop() {
        String rule = declarations(".dash.expanded .panel-open");

        assertThat(rule).contains("top: 14px").contains("bottom: auto");
    }

    @Test
    @DisplayName("FNC-C-016 캠 패널을 펼치면 버튼이 보일 때 범례를 버튼 오른쪽으로 민다")
    void expandedDash_shiftsLegendRightOfPanelOpenButton() {
        String rule = declarations(".dash.expanded .panel-open:not([hidden]) ~ .map-legend");

        assertThat(rule).containsPattern("left:\\s*(7\\d|8\\d)px");
    }
}
