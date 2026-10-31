package com.voucherx.order.service;

/**
 * 明确无法通过重试解决的业务失败。
 */
public class OrderBusinessException extends RuntimeException {

    private final OrderFailure failure;

    public OrderBusinessException(OrderFailure failure, String message) {
        super(message);
        this.failure = failure;
    }

    public OrderFailure failure() {
        return failure;
    }
}
