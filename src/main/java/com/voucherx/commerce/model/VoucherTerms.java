package com.voucherx.commerce.model;

/**
 * 建单时从券模板读取的规则，用于固定购买订单的付款与权益快照。
 *
 * @param payValue
 *                            买券价格，分
 * @param actualValue
 *                            购买后所发券的面额，分
 * @param shopId
 *                            券可用商户 ID
 * @param minSpend
 *                            使用门槛，分
 * @param validitySeconds
 *                            成功付款后券有效时长，秒
 */
public record VoucherTerms(long payValue, long actualValue, long shopId, long minSpend, long validitySeconds) {
}
