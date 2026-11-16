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
}
