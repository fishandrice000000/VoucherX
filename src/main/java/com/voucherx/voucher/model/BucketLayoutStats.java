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
     * 数据库查询得到的实际桶行数。
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
     * 以实际行数及编号范围核对 expectedCount 个桶的覆盖。
     * <p>
     * 数量应相等，最小编号为 0，最大编号为 expectedCount-1；活动内桶号唯一约束保证此范围连续。
     * 调用者另行查询总额度和剩余量，完成库存事实核验。
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
