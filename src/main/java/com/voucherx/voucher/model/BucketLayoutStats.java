package com.voucherx.voucher.model;

/**
 * 桶布局统计。用于验证桶编号是否连续覆盖 0..bucketCount-1，
 * 不能只校验行数和额度总和。
 */
public class BucketLayoutStats {

    private Integer bucketCount;
    private Integer minBucketNo;
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

    public boolean covers(int expectedCount) {
        return bucketCount != null && bucketCount == expectedCount
                && minBucketNo != null && minBucketNo == 0
                && maxBucketNo != null && maxBucketNo == expectedCount - 1;
    }
}
