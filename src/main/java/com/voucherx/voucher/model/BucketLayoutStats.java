package com.voucherx.voucher.model;

/**
 * 指定活动、布局版本下的桶数量及最小/最大编号，来自 Mapper 聚合查询。
 * <p>
 * 只有行数相同不足以证明布局正确：4 行编号为 0、1、2、99 时仍缺桶 3。
 * {@link #covers(int)} 同时核验数量和范围；桶号不重复由表主键 (voucher_id, bucket_no) 保证。
 *
 * @see com.voucherx.voucher.mapper.SeckillVoucherBucketMapper#selectLayoutStats
 */
public class BucketLayoutStats {

    /**
     * 该布局实际查到的桶行数，不是活动配置中的预期桶数。
     */
    private Integer bucketCount;
    /**
     * 该布局最小桶号；聚合没有桶行时为 null。
     */
    private Integer minBucketNo;
    /**
     * 该布局最大桶号；聚合没有桶行时为 null。
     */
    private Integer maxBucketNo;

    public Integer getBucketCount() {
        return bucketCount;
    }

    public void setBucketCount(Integer bucketCount) {
        this.bucketCount = bucketCount;
    }

    public Integer getMinBucketNo() {
        return minBucketNo;
    }

    public void setMinBucketNo(Integer minBucketNo) {
        this.minBucketNo = minBucketNo;
    }

    public Integer getMaxBucketNo() {
        return maxBucketNo;
    }

    public void setMaxBucketNo(Integer maxBucketNo) {
        this.maxBucketNo = maxBucketNo;
    }

    /**
     * 检查数量等于预期且编号范围恰为 0 到 expectedCount-1。
     * <p>
     * 结合数据库对同一活动桶号的唯一约束，才能推得编号连续且没有缺口。
     * 本方法不读取数据库、不核对库存总量或剩余量；调用方还需另做额度核验。
     *
     * @param expectedCount
     *                          活动配置中已经验证为正数的桶数量
     * @return 数量、起点和终点都符合预期时为 true；统计缺失则为 false
     */
    public boolean covers(int expectedCount) {
        return bucketCount != null && bucketCount == expectedCount
                && minBucketNo != null && minBucketNo == 0
                && maxBucketNo != null && maxBucketNo == expectedCount - 1;
    }
}
