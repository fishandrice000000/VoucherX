package com.voucherx.fulfillment;

import com.voucherx.auth.interceptor.TokenExtractor;
import com.voucherx.voucher.entity.SeckillVoucher;
import com.voucherx.voucher.entity.StockMode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;

/** 当前接口与库存布局的纯规则测试；不启动基础设施。 */
class CurrentContractTest {
    /** 仅 Bearer 凭证能被提取，裸 Token、其他方案及空凭证均拒绝。 */
    @Test
    void onlyBearerCredentialsAreAccepted() {
        var request = new MockHttpServletRequest();
        assertThat(TokenExtractor.extract(request)).isNull();
        for (String header : new String[] { "token", "Basic token", "Bearer ", " " }) {
            request.removeHeader("Authorization");
            request.addHeader("Authorization", header);
            assertThat(TokenExtractor.extract(request)).isNull();
        }
        request.removeHeader("Authorization");
        request.addHeader("Authorization", "bEaReR token");
        assertThat(TokenExtractor.extract(request)).isEqualTo("token");
    }

    /**
     * 验证库存模式必须明确为 SINGLE 或 BUCKET，缺失及未知值均拒绝。
     */
    @Test
    void stockModeRequiresAnExplicitSupportedValue() {
        for (String mode : new String[] { null, "", " ", "single", "UNKNOWN" })
            assertThatThrownBy(() -> StockMode.parse(mode)).isInstanceOf(IllegalArgumentException.class);
        assertThat(StockMode.parse("SINGLE")).isEqualTo(StockMode.SINGLE);
        assertThat(StockMode.parse("BUCKET")).isEqualTo(StockMode.BUCKET);
    }

    /** 布局字段须完整且为有效值，单库存桶数固定为一。 */
    @Test
    void layoutCannotUseMissingOrInvalidDefaults() {
        var activity = new SeckillVoucher();
        activity.setStockMode("SINGLE");
        assertThatThrownBy(() -> StockMode.requireLayout(activity)).isInstanceOf(IllegalArgumentException.class);
        activity.setBucketCount(1);
        activity.setLayoutVersion(0);
        assertThatThrownBy(() -> StockMode.requireLayout(activity)).isInstanceOf(IllegalArgumentException.class);
        activity.setLayoutVersion(1);
        activity.setBucketCount(2);
        assertThatThrownBy(() -> StockMode.requireLayout(activity)).isInstanceOf(IllegalArgumentException.class);
        activity.setStockMode("BUCKET");
        assertThat(StockMode.requireLayout(activity)).isEqualTo(StockMode.BUCKET);
        activity.setBucketCount(0);
        assertThatThrownBy(() -> StockMode.requireLayout(activity)).isInstanceOf(IllegalArgumentException.class);
    }

    /** 建单事件 JSON 与账本保持相同 eventId，反序列化后保留完整业务身份。 */
    @Test
    void orderEventJsonPreservesLedgerIdentity() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var request = new com.voucherx.fulfillment.RequestRecord("R1", "E1", 1001, 2001,
                "ACCEPTED", "RESERVED", 1, "SINGLE", 0, 1, null, null, 100, 100);
        var payload = json.writeValueAsBytes(request.message());
        var fields = json.readTree(payload);
        java.util.Set<String> names = new java.util.HashSet<>();
        fields.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrder("eventId", "requestId", "userId", "voucherId",
                "createdAt", "stockMode", "startBucketNo", "layoutVersion", "epoch");
        assertThat(fields.get("eventId").asText()).isEqualTo("E1");
        assertThat(request.matches(json.readValue(payload, com.voucherx.seckill.model.SeckillOrderMessage.class)))
                .isTrue();
    }
}
